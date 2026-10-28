package io.github.cskwork.lunepick.member.internal.config;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import io.github.cskwork.lunepick.member.internal.application.AuthTransactions;
import io.github.cskwork.lunepick.member.internal.application.BoundedPasswordEncoder;
import io.github.cskwork.lunepick.member.internal.application.RefreshTokenCipher;
import io.github.cskwork.lunepick.member.internal.domain.OpaqueTokens;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.converter.RsaKeyConverters;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.DelegatingPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.Pbkdf2PasswordEncoder;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AuthProperties.class, RefreshCipherProperties.class})
class AuthConfiguration {
    // Deliberately public synthetic development material; rejected in production.
    static final String LOCAL_REFRESH_SECRET =
            Base64.getEncoder()
                    .encodeToString(
                            "lunepick-development-refresh-key".getBytes(StandardCharsets.US_ASCII));

    @Bean
    OpaqueTokens opaqueTokens() {
        return new OpaqueTokens();
    }

    @Bean
    PasswordEncoder passwordEncoder(PasswordHashProperties properties) {
        // PBKDF2 accepts the complete validated password, including multibyte characters.
        // A delegating prefix also permits future migration and existing bcrypt hashes.
        PasswordEncoder delegate =
                new DelegatingPasswordEncoder(
                        "pbkdf2",
                        Map.of(
                                "pbkdf2",
                                Pbkdf2PasswordEncoder.defaultsForSpringSecurity_v5_8(),
                                "bcrypt",
                                new BCryptPasswordEncoder()));
        return new BoundedPasswordEncoder(
                delegate, properties.concurrency(), properties.waitTimeout());
    }

    @Bean
    KeyPair authKeyPair(AuthProperties properties, Environment environment) throws Exception {
        if (properties.privateKey() != null && properties.publicKey() != null) {
            RSAPrivateKey privateKey;
            RSAPublicKey publicKey;
            try (InputStream privateStream = properties.privateKey().getInputStream();
                    InputStream publicStream = properties.publicKey().getInputStream()) {
                privateKey = RsaKeyConverters.pkcs8().convert(privateStream);
                publicKey = RsaKeyConverters.x509().convert(publicStream);
            }
            if (privateKey == null
                    || publicKey == null
                    || publicKey.getModulus().bitLength() < 2048
                    || !privateKey.getModulus().equals(publicKey.getModulus())) {
                throw new IllegalStateException(
                        "Authentication RSA keys must match and contain at least 2048 bits");
            }
            return new KeyPair(publicKey, privateKey);
        }
        if (properties.privateKey() != null || properties.publicKey() != null) {
            throw new IllegalStateException(
                    "Both lunepick.auth.private-key and public-key must be configured");
        }
        boolean development =
                !environment.acceptsProfiles(Profiles.of("prod", "production"))
                        && environment.acceptsProfiles(Profiles.of("local", "test"));
        if (!development) {
            throw new IllegalStateException(
                    "RSA signing keys are required outside local/test profiles");
        }
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair();
    }

    @Bean
    RefreshTokenCipher refreshTokenCipher(
            RefreshCipherProperties properties, Environment environment) {
        boolean development =
                !environment.acceptsProfiles(Profiles.of("prod", "production"))
                        && environment.acceptsProfiles(Profiles.of("local", "test"));
        String secret = properties.secret();
        if (secret == null && development) secret = LOCAL_REFRESH_SECRET;
        if (secret == null || (!development && secret.equals(LOCAL_REFRESH_SECRET)))
            throw new IllegalStateException(
                    "A dedicated lunepick.auth.refresh-cipher.secret is required outside local/test profiles");
        return new RefreshTokenCipher(new RefreshCipherProperties(secret).keyBytes());
    }

    @Bean
    JwtEncoder jwtEncoder(KeyPair authKeyPair) {
        RSAKey key =
                new RSAKey.Builder((RSAPublicKey) authKeyPair.getPublic())
                        .privateKey((RSAPrivateKey) authKeyPair.getPrivate())
                        .keyID("lunepick-signing")
                        .build();
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
    }

    @Bean
    JwtDecoder jwtDecoder(
            KeyPair authKeyPair,
            AuthProperties properties,
            AuthTransactions transactions,
            Clock clock) {
        NimbusJwtDecoder decoder =
                NimbusJwtDecoder.withPublicKey((RSAPublicKey) authKeyPair.getPublic()).build();
        JwtTimestampValidator timestamps = new JwtTimestampValidator(Duration.ZERO);
        timestamps.setClock(clock);
        OAuth2TokenValidator<Jwt> session =
                jwt -> {
                    try {
                        UUID familyId = UUID.fromString(jwt.getClaimAsString("sid"));
                        long memberId = Long.parseLong(jwt.getSubject());
                        if (jwt.getExpiresAt() != null
                                && jwt.getIssuedAt() != null
                                && memberId > 0
                                && transactions.activeSession(familyId, memberId)) {
                            return OAuth2TokenValidatorResult.success();
                        }
                    } catch (IllegalArgumentException | NullPointerException malformedClaims) {
                        // Malformed signed claims are authentication failures, never server errors.
                    }
                    return OAuth2TokenValidatorResult.failure(
                            new OAuth2Error("invalid_token", "Session is inactive", null));
                };
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(
                        timestamps,
                        new JwtIssuerValidator(properties.issuer()),
                        new JwtClaimValidator<List<String>>(
                                "aud",
                                audience ->
                                        audience != null
                                                && audience.contains(properties.audience())),
                        session));
        return decoder;
    }
}
