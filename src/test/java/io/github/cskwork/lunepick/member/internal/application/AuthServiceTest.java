package io.github.cskwork.lunepick.member.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.member.internal.config.AuthProperties;
import io.github.cskwork.lunepick.shared.ApiException;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    @Mock AuthTransactions transactions;
    @Mock PasswordEncoder passwords;
    @Mock JwtEncoder encoder;
    private AuthService auth;

    @BeforeEach
    void setUp() {
        when(passwords.encode(anyString())).thenReturn("encoded");
        auth =
                new AuthService(
                        transactions,
                        passwords,
                        encoder,
                        new AuthProperties(null, null, null, null, null),
                        Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void signupHashesPasswordBeforeOpeningWriteTransaction() {
        var request = new AuthDtos.SignupRequest("USER@example.com", "abcdefgh", "회원", null);
        when(transactions.signup(request, "encoded")).thenReturn(7L);
        assertThat(auth.signup(request).memberId()).isEqualTo(7L);
        var ordered = inOrder(passwords, transactions);
        ordered.verify(passwords).encode("abcdefgh");
        ordered.verify(transactions).signup(request, "encoded");
    }

    @Test
    void signupRejectsPasswordLengthBeforeHashing() {
        clearInvocations(passwords);
        assertThatThrownBy(
                        () ->
                                auth.signup(
                                        new AuthDtos.SignupRequest(
                                                "x@example.com", "short", "회원", null)))
                .isInstanceOfSatisfying(
                        ApiException.class, error -> assertThat(error.status()).isEqualTo(400));
        verifyNoInteractions(transactions, passwords);
    }

    @Test
    void concurrentDuplicateEmailMapsDatabaseConflictAfterRollback() {
        var request = new AuthDtos.SignupRequest("user@example.com", "abcdefgh", "회원", null);
        when(transactions.signup(request, "encoded"))
                .thenThrow(new DataIntegrityViolationException("unique"));
        when(transactions.credentials(request.email()))
                .thenReturn(Optional.of(new AuthTransactions.Credentials(7L, "encoded")));
        assertThatThrownBy(() -> auth.signup(request))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(409);
                            assertThat(error.code()).isEqualTo("EMAIL_DUPLICATED");
                        });
    }

    @Test
    void unrelatedDatabaseConflictIsNotReportedAsDuplicateEmail() {
        var request = new AuthDtos.SignupRequest("user@example.com", "abcdefgh", "회원", null);
        when(transactions.signup(request, "encoded"))
                .thenThrow(new DataIntegrityViolationException("other"));
        when(transactions.credentials(request.email())).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.signup(request))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void successfulLoginVerifiesPasswordBeforeSessionWriteAndIssuesRs256WithSid() {
        UUID familyId = UUID.randomUUID();
        when(transactions.credentials("user@example.com"))
                .thenReturn(Optional.of(new AuthTransactions.Credentials(7L, "stored")));
        when(passwords.matches("abcdefgh", "stored")).thenReturn(true);
        when(transactions.startSession(7L))
                .thenReturn(new AuthTransactions.SessionTokens(familyId, 7L, "refresh"));
        givenEncoder();
        var result = auth.login(new AuthDtos.LoginRequest("USER@example.com", "abcdefgh"));
        assertThat(result).isEqualTo(new AuthDtos.TokenResponse("signed-access", "refresh", 1800));
        var ordered = inOrder(transactions, passwords);
        ordered.verify(transactions).credentials("user@example.com");
        ordered.verify(passwords).matches("abcdefgh", "stored");
        ordered.verify(transactions).startSession(7L);
        var captor = ArgumentCaptor.forClass(JwtEncoderParameters.class);
        verify(encoder).encode(captor.capture());
        var parameters = captor.getValue();
        assertThat(parameters.getJwsHeader().getAlgorithm().getName()).isEqualTo("RS256");
        assertThat(parameters.getClaims().getSubject()).isEqualTo("7");
        assertThat(parameters.getClaims().getClaimAsString("iss")).isEqualTo("lunepick");
        assertThat(parameters.getClaims().getClaimAsString("sid")).isEqualTo(familyId.toString());
        assertThat(parameters.getClaims().getExpiresAt()).isEqualTo(NOW.plusSeconds(1800));
    }

    @Test
    void wrongPasswordAndUnknownMemberShareSameErrorAndStillHash() {
        when(transactions.credentials("user@example.com"))
                .thenReturn(Optional.of(new AuthTransactions.Credentials(7L, "stored")));
        when(transactions.credentials("missing@example.com")).thenReturn(Optional.empty());
        assertInvalidCredentials("user@example.com");
        assertInvalidCredentials("missing@example.com");
        verify(passwords).matches("bad", "stored");
        verify(passwords).matches("bad", "encoded");
        verify(transactions, never()).startSession(anyLong());
    }

    @Test
    void unknownMemberCannotLoginEvenIfDummyPasswordVerificationMatches() {
        when(transactions.credentials(anyString())).thenReturn(Optional.empty());
        when(passwords.matches(anyString(), anyString())).thenReturn(true);
        assertInvalidCredentials("missing@example.com");
    }

    @Test
    void refreshReturnsNewTokensAfterRotation() {
        when(transactions.rotate("old"))
                .thenReturn(
                        Optional.of(
                                new AuthTransactions.SessionTokens(
                                        UUID.randomUUID(), 7L, "new-refresh")));
        givenEncoder();
        assertThat(auth.refresh(new AuthDtos.RefreshRequest("old")).refreshToken())
                .isEqualTo("new-refresh");
    }

    @Test
    void invalidRefreshThrowsOutsideRotationTransaction() {
        when(transactions.rotate("replayed")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.refresh(new AuthDtos.RefreshRequest("replayed")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(401);
                            assertThat(error.code()).isEqualTo("INVALID_TOKEN");
                        });
        verifyNoInteractions(encoder);
    }

    @Test
    void logoutUsesAuthenticatedSubjectAndSession() {
        UUID familyId = UUID.randomUUID();
        auth.logout(
                Jwt.withTokenValue("access")
                        .header("alg", "RS256")
                        .subject("7")
                        .claim("sid", familyId.toString())
                        .build());
        verify(transactions).logout(familyId, 7L);
    }

    @Test
    void demoInitializerCreatesSampleOnlyIfAbsent() {
        var demo = new DemoMemberInitializer(auth, transactions);
        when(transactions.credentials("demo@example.com"))
                .thenReturn(Optional.of(new AuthTransactions.Credentials(1L, "encoded")));
        demo.run(null);
        verify(transactions, never()).signup(any(), anyString());
        when(transactions.credentials("demo@example.com")).thenReturn(Optional.empty());
        demo.run(null);
        verify(transactions)
                .signup(
                        new AuthDtos.SignupRequest("demo@example.com", "demo1234", "데모 회원", null),
                        "encoded");
    }

    private void givenEncoder() {
        when(encoder.encode(any()))
                .thenReturn(
                        Jwt.withTokenValue("signed-access")
                                .header("alg", "RS256")
                                .subject("7")
                                .build());
    }

    private void assertInvalidCredentials(String email) {
        assertThatThrownBy(() -> auth.login(new AuthDtos.LoginRequest(email, "bad")))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> {
                            assertThat(error.status()).isEqualTo(401);
                            assertThat(error.code()).isEqualTo("INVALID_CREDENTIALS");
                        });
    }
}
