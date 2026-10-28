package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.member.internal.config.AuthProperties;
import io.github.cskwork.lunepick.member.internal.domain.PasswordPolicy;
import io.github.cskwork.lunepick.shared.ApiException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final AuthTransactions transactions;
    private final PasswordEncoder passwords;
    private final JwtEncoder encoder;
    private final AuthProperties properties;
    private final Clock clock;
    private final String dummyPasswordHash;

    AuthService(
            AuthTransactions transactions,
            PasswordEncoder passwords,
            JwtEncoder encoder,
            AuthProperties properties,
            Clock clock) {
        this.transactions = transactions;
        this.passwords = passwords;
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
        this.dummyPasswordHash = passwords.encode(UUID.randomUUID().toString());
    }

    public AuthDtos.SignupResponse signup(AuthDtos.SignupRequest request) {
        if (!PasswordPolicy.valid(request.password())) {
            throw new ApiException(400, "VALIDATION_FAILED", "비밀번호는 8자 이상 256자 이하여야 합니다.");
        }
        String passwordHash = passwords.encode(request.password());
        try {
            return new AuthDtos.SignupResponse(transactions.signup(request, passwordHash));
        } catch (DataIntegrityViolationException conflict) {
            // The email unique constraint closes the concurrent signup race.
            if (transactions.credentials(request.email()).isPresent()) {
                throw new ApiException(409, "EMAIL_DUPLICATED", "이미 가입된 이메일입니다.");
            }
            throw conflict;
        }
    }

    public AuthDtos.TokenResponse login(AuthDtos.LoginRequest request) {
        var credentials = transactions.credentials(request.email());
        boolean matches =
                passwords.matches(
                        request.password(),
                        credentials
                                .map(AuthTransactions.Credentials::passwordHash)
                                .orElse(dummyPasswordHash));
        if (credentials.isEmpty() || !matches) {
            throw new ApiException(401, "INVALID_CREDENTIALS", "이메일 또는 비밀번호가 올바르지 않습니다.");
        }
        return accessToken(transactions.startSession(credentials.get().memberId()));
    }

    public AuthDtos.TokenResponse refresh(AuthDtos.RefreshRequest request) {
        // rotate() has completed its transaction before invalid-token errors are raised.
        return transactions
                .rotate(request.refreshToken())
                .map(this::accessToken)
                .orElseThrow(() -> new ApiException(401, "INVALID_TOKEN", "세션이 만료되었습니다."));
    }

    public void logout(Jwt jwt) {
        transactions.logout(
                UUID.fromString(jwt.getClaimAsString("sid")), Long.parseLong(jwt.getSubject()));
    }

    private AuthDtos.TokenResponse accessToken(AuthTransactions.SessionTokens session) {
        Instant now = clock.instant();
        JwtClaimsSet claims =
                JwtClaimsSet.builder()
                        .issuer(properties.issuer())
                        .audience(List.of(properties.audience()))
                        .subject(Long.toString(session.memberId()))
                        .issuedAt(now)
                        .expiresAt(now.plus(properties.accessTtl()))
                        .id(UUID.randomUUID().toString())
                        .claim("sid", session.familyId().toString())
                        .build();
        String access =
                encoder.encode(
                                JwtEncoderParameters.from(
                                        JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                        .getTokenValue();
        return new AuthDtos.TokenResponse(
                access, session.refreshToken(), properties.accessTtl().toSeconds());
    }
}
