package io.github.cskwork.lunepick.member.internal.application;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.member.MemberRegistered;
import io.github.cskwork.lunepick.member.internal.config.AuthProperties;
import io.github.cskwork.lunepick.member.internal.domain.OpaqueTokens;
import io.github.cskwork.lunepick.member.internal.persistence.*;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class AuthTransactionsTest {
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");
    @Mock MemberRepository members;
    @Mock JdbcMemberPointsStore points;
    @Mock RefreshFamilyRepository families;
    @Mock RefreshTokenRepository tokens;
    @Mock ApplicationEventPublisher events;
    private final OpaqueTokens opaque = new OpaqueTokens();
    private AuthTransactions auth;

    @BeforeEach
    void setUp() {
        auth =
                new AuthTransactions(
                        members,
                        points,
                        families,
                        tokens,
                        opaque,
                        new RefreshTokenCipher(new byte[32]),
                        new AuthProperties(null, null, null, null, null),
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        events);
    }

    @Test
    void signupStoresNormalizedEmailAndEncodedPassword() {
        var request = new AuthDtos.SignupRequest(" USER@Example.com ", "abcdefgh", " 사용자 ", "");
        when(members.saveAndFlush(any()))
                .thenAnswer(
                        invocation -> {
                            Member member = invocation.getArgument(0);
                            ReflectionTestUtils.setField(member, "id", 9L);
                            assertThat(ReflectionTestUtils.getField(member, "email"))
                                    .isEqualTo("user@example.com");
                            assertThat(member.passwordHash()).isEqualTo("encoded");
                            assertThat(member.name()).isEqualTo("사용자");
                            assertThat(ReflectionTestUtils.getField(member, "phone")).isNull();
                            return member;
                        });
        assertThat(auth.signup(request, "encoded")).isEqualTo(9);
        verify(events).publishEvent(new MemberRegistered(9));
    }

    @Test
    void duplicateSignupDoesNotInsertMember() {
        when(members.existsByEmail("used@example.com")).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                auth.signup(
                                        new AuthDtos.SignupRequest(
                                                "used@example.com", "abcdefgh", "회원", null),
                                        "encoded"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        error -> assertThat(error.code()).isEqualTo("EMAIL_DUPLICATED"));
        verify(members, never()).saveAndFlush(any());
        verifyNoInteractions(events);
    }

    @Test
    void loginSessionPersistsOnlyTokenHashAndFixedFamilyExpiry() {
        when(families.saveAndFlush(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = auth.startSession(7L);
        var token = ArgumentCaptor.forClass(RefreshToken.class);
        verify(tokens).save(token.capture());
        assertThat(ReflectionTestUtils.getField(token.getValue(), "tokenHash"))
                .isEqualTo(opaque.hash(result.refreshToken()));
        assertThat(ReflectionTestUtils.getField(token.getValue(), "expiresAt"))
                .isEqualTo(NOW.plus(Duration.ofDays(30)));
        assertThat(result.memberId()).isEqualTo(7L);
    }

    @Test
    void rotationLocksFamilyBeforeLoadingTokenAndConsumesOldToken() {
        RefreshFamily family = family();
        RefreshToken token = givenToken(family);
        var rotated = auth.rotate("old").orElseThrow();
        assertThat(token.consumed()).isTrue();
        assertThat(rotated.familyId()).isEqualTo(family.id());
        assertThat(rotated.refreshToken()).isNotEqualTo("old");
        var ordered = inOrder(tokens, families);
        ordered.verify(tokens).findFamilyIdByTokenHash(opaque.hash("old"));
        ordered.verify(families).findLockedById(family.id());
        ordered.verify(tokens).findByTokenHash(opaque.hash("old"));
        ordered.verify(tokens).save(any());
    }

    @Test
    void replayRevokesFamilyAndReturnsNormallySoTransactionCanCommit() {
        RefreshFamily family = family();
        RefreshToken token = givenToken(family);
        token.consume(NOW.minusSeconds(1));
        assertThat(auth.rotate("old")).isEmpty();
        assertThat(family.activeAt(NOW)).isFalse();
        verify(tokens, never()).save(any());
    }

    @Test
    void immediatelyPreviousTokenReturnsSameSuccessorDuringGrace() {
        RefreshFamily family = family();
        givenToken(family);
        var first = auth.rotate("old").orElseThrow();
        var retry = auth.rotate("old").orElseThrow();
        assertThat(retry.refreshToken()).isEqualTo(first.refreshToken());
        assertThat(family.activeAt(NOW)).isTrue();
        verify(tokens, times(1)).save(any());
        assertThat(family.successorToken()).doesNotContain(first.refreshToken());
    }

    @Test
    void previousTokenOutsideGraceRevokesFamily() {
        RefreshFamily family = family();
        RefreshToken token = givenToken(family);
        token.consume(NOW.minusSeconds(3));
        family.previousToken(
                opaque.hash("old"), new RefreshTokenCipher(new byte[32]).encrypt("next"), NOW);
        assertThat(auth.rotate("old")).isEmpty();
        assertThat(family.activeAt(NOW)).isFalse();
        assertThat(family.successorToken()).isNull();
    }

    @Test
    void unknownTokenNeverTakesFamilyLock() {
        when(tokens.findFamilyIdByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(auth.rotate("missing")).isEmpty();
        verifyNoInteractions(families);
    }

    @Test
    void expiredOrRevokedSessionCannotRotate() {
        RefreshFamily family = new RefreshFamily(7L, NOW.minusSeconds(20), NOW);
        when(tokens.findFamilyIdByTokenHash(anyString())).thenReturn(Optional.of(family.id()));
        when(families.findLockedById(family.id())).thenReturn(Optional.of(family));
        assertThat(auth.rotate("old")).isEmpty();
        verify(tokens, never()).findByTokenHash(anyString());
    }

    @Test
    void expiryBoundaryRejectsUnconsumedToken() {
        RefreshFamily family = family();
        RefreshToken token = givenToken(family);
        ReflectionTestUtils.setField(token, "expiresAt", NOW);
        assertThat(auth.rotate("old")).isEmpty();
        assertThat(token.consumed()).isFalse();
    }

    @Test
    void missingFamilyOrMissingTokenIsExplicitlyInvalid() {
        UUID id = UUID.randomUUID();
        when(tokens.findFamilyIdByTokenHash(anyString())).thenReturn(Optional.of(id));
        when(families.findLockedById(id)).thenReturn(Optional.empty());
        assertThat(auth.rotate("old")).isEmpty();
        RefreshFamily family = family();
        when(tokens.findFamilyIdByTokenHash(anyString())).thenReturn(Optional.of(family.id()));
        when(families.findLockedById(family.id())).thenReturn(Optional.of(family));
        when(tokens.findByTokenHash(anyString())).thenReturn(Optional.empty());
        assertThat(auth.rotate("old")).isEmpty();
    }

    @Test
    void logoutRevokesOnlyTheAuthenticatedMembersFamily() {
        RefreshFamily family = family();
        when(families.findLockedById(family.id())).thenReturn(Optional.of(family));
        auth.logout(family.id(), 8L);
        assertThat(family.activeAt(NOW)).isTrue();
        auth.logout(family.id(), 7L);
        assertThat(family.activeAt(NOW)).isFalse();
        family.revoke(NOW.plusSeconds(1));
        assertThat(ReflectionTestUtils.getField(family, "revokedAt")).isEqualTo(NOW);
    }

    @Test
    void activeSessionRequiresMatchingMemberAndNonExpiredNonRevokedFamily() {
        RefreshFamily family = family();
        when(families.findById(family.id())).thenReturn(Optional.of(family));
        assertThat(auth.activeSession(family.id(), 7L)).isTrue();
        assertThat(auth.activeSession(family.id(), 8L)).isFalse();
        family.revoke(NOW);
        assertThat(auth.activeSession(family.id(), 7L)).isFalse();
        assertThat(auth.activeSession(UUID.randomUUID(), 7L)).isFalse();
    }

    @Test
    void credentialAndPublicLookupExposeOnlyRequiredData() {
        Member member = new Member("user@example.com", "encoded", "회원", null, NOW);
        ReflectionTestUtils.setField(member, "id", 7L);
        when(members.findByEmail("user@example.com")).thenReturn(Optional.of(member));
        when(members.findById(7L)).thenReturn(Optional.of(member));
        assertThat(auth.credentials("USER@Example.com"))
                .contains(new AuthTransactions.Credentials(7L, "encoded"));
        assertThat(auth.findMember(7L))
                .hasValueSatisfying(value -> assertThat(value.name()).isEqualTo("회원"));
    }

    private RefreshFamily family() {
        return new RefreshFamily(7L, NOW.minusSeconds(10), NOW.plusSeconds(100));
    }

    private RefreshToken givenToken(RefreshFamily family) {
        RefreshToken token =
                new RefreshToken(
                        family.id(), opaque.hash("old"), NOW.minusSeconds(10), family.expiresAt());
        when(tokens.findFamilyIdByTokenHash(opaque.hash("old")))
                .thenReturn(Optional.of(family.id()));
        when(families.findLockedById(family.id())).thenReturn(Optional.of(family));
        when(tokens.findByTokenHash(opaque.hash("old"))).thenReturn(Optional.of(token));
        return token;
    }

    @Test
    void pointReservationsValidateIntegersAndAccountCapacity() {
        var amount = new BigDecimal("1000");
        when(points.availablePoints(1)).thenReturn(Optional.of(new BigDecimal("3000")));
        assertThat(auth.availablePoints(1)).isEqualByComparingTo("3000");
        when(points.availablePoints(2)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> auth.availablePoints(2)).isInstanceOf(ApiException.class);
        for (var invalid : List.of(new BigDecimal("-1"), new BigDecimal("1.5")))
            assertThatThrownBy(() -> auth.reservePoints(1, invalid))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            error -> assertThat(error.code()).isEqualTo("INSUFFICIENT_POINTS"));
        assertThatThrownBy(() -> auth.reservePoints(1, null)).isInstanceOf(ApiException.class);
        when(points.reservePoints(1, amount)).thenReturn(0, 1);
        assertThatThrownBy(() -> auth.reservePoints(1, amount)).isInstanceOf(ApiException.class);
        auth.reservePoints(1, amount);
        when(points.settlePoints(1, amount)).thenReturn(0, 1);
        assertThatThrownBy(() -> auth.settlePoints(1, amount)).isInstanceOf(ApiException.class);
        auth.settlePoints(1, amount);
        when(points.releasePoints(1, amount)).thenReturn(0, 1);
        assertThatThrownBy(() -> auth.releasePoints(1, amount)).isInstanceOf(ApiException.class);
        auth.releasePoints(1, amount);
    }
}
