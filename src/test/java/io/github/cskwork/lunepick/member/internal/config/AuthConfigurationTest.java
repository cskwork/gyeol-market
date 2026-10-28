package io.github.cskwork.lunepick.member.internal.config;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.member.internal.application.AuthTransactions;
import java.security.*;
import java.time.*;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;

class AuthConfigurationTest {
    private final AuthConfiguration config = new AuthConfiguration();
    private final AuthProperties defaults = new AuthProperties(null, null, null, null, null);
    private final Instant now = Instant.parse("2026-10-28T00:00:00Z");

    @Test
    void refreshCipherRequiresIndependentConfiguredSecretOutsideDevelopment() {
        for (var profiles :
                List.of(
                        new String[] {"prod"},
                        new String[] {"prod", "local", "test"},
                        new String[] {"production", "local"})) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(profiles);
            assertThatThrownBy(
                            () ->
                                    config.refreshTokenCipher(
                                            new RefreshCipherProperties(null), environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required outside local/test");
            assertThatThrownBy(
                            () ->
                                    config.refreshTokenCipher(
                                            new RefreshCipherProperties(
                                                    AuthConfiguration.LOCAL_REFRESH_SECRET),
                                            environment))
                    .isInstanceOf(IllegalStateException.class);
        }
        var development = new MockEnvironment();
        development.setActiveProfiles("test");
        var first = config.refreshTokenCipher(new RefreshCipherProperties(null), development);
        var second = config.refreshTokenCipher(new RefreshCipherProperties(null), development);
        assertThat(second.decrypt(first.encrypt("refresh"))).isEqualTo("refresh");

        byte[] independentKey = new byte[32];
        new SecureRandom().nextBytes(independentKey);
        var properties =
                new RefreshCipherProperties(Base64.getEncoder().encodeToString(independentKey));
        var production = new MockEnvironment();
        production.setActiveProfiles("prod");
        var configured = config.refreshTokenCipher(properties, production);
        assertThat(
                        config.refreshTokenCipher(properties, production)
                                .decrypt(configured.encrypt("refresh")))
                .isEqualTo("refresh");
    }

    @Test
    void refreshCipherSecretValidationNeverEchoesInvalidMaterial() {
        for (String malformed :
                List.of("secret-material", "", Base64.getEncoder().encodeToString(new byte[31])))
            assertThatThrownBy(() -> new RefreshCipherProperties(malformed))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("exactly 32 bytes");
    }

    @Test
    void configuredDefaultsHavePositiveBoundedTokenLifetimes() {
        assertThat(defaults.issuer()).isEqualTo("lunepick");
        assertThat(defaults.accessTtl()).isEqualTo(Duration.ofMinutes(30));
        assertThat(defaults.refreshTtl()).isEqualTo(Duration.ofDays(30));
        assertThat(defaults.isTokenLifetimeValid()).isTrue();
        assertThat(
                        new AuthProperties("issuer", Duration.ZERO, null, null, null)
                                .isTokenLifetimeValid())
                .isFalse();
        assertThat(
                        new AuthProperties("issuer", Duration.ofHours(2), null, null, null)
                                .isTokenLifetimeValid())
                .isFalse();
        assertThat(
                        new AuthProperties(
                                        "issuer",
                                        Duration.ofMinutes(30),
                                        Duration.ofMinutes(1),
                                        null,
                                        null)
                                .isTokenLifetimeValid())
                .isFalse();
    }

    @Test
    void passwordsUseDelegatingHashAndPreserveUnicodeBeyondBcryptByteLimit() {
        var encoder = config.passwordEncoder(new PasswordHashProperties(null, null));
        String password = "가".repeat(30);
        String hash = encoder.encode(password);
        assertThat(hash).startsWith("{pbkdf2}").doesNotContain(password);
        assertThat(encoder.matches(password, hash)).isTrue();
        assertThat(encoder.matches("wrong", hash)).isFalse();
    }

    @Test
    void productionWithoutKeysFailsFastAndPartialKeysAlsoFailInLocal() {
        assertThatThrownBy(
                        () ->
                                config.authKeyPair(
                                        defaults,
                                        new MockEnvironment()
                                                .withProperty(
                                                        "spring.profiles.active", "production")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("required outside local/test");
        var partial =
                new AuthProperties(null, null, null, new ByteArrayResource(new byte[0]), null);
        assertThatThrownBy(
                        () ->
                                config.authKeyPair(
                                        partial,
                                        new MockEnvironment()
                                                .withProperty("spring.profiles.active", "local")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Both");
    }

    @Test
    void productionProfilesNeverAllowEphemeralKeysEvenAlongsideDevelopmentProfiles() {
        for (String production : List.of("prod", "production")) {
            var environment = new MockEnvironment();
            environment.setActiveProfiles(production, "local", "test");
            assertThatThrownBy(() -> config.authKeyPair(defaults, environment))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("required outside local/test");
        }
    }

    @Test
    void productionApiDocumentationIsDisabledUnlessExplicitlyEnabled() throws Exception {
        var source =
                new YamlPropertySourceLoader()
                        .load("production", new ClassPathResource("application-prod.yml"))
                        .getFirst();
        var environment = new MockEnvironment();
        environment.getPropertySources().addLast(source);
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class))
                .isFalse();
        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isFalse();
        environment
                .withProperty("LUNEPICK_SWAGGER_UI_ENABLED", "true")
                .withProperty("LUNEPICK_API_DOCS_ENABLED", "true");
        assertThat(environment.getProperty("springdoc.swagger-ui.enabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("springdoc.api-docs.enabled", Boolean.class)).isTrue();
    }

    @Test
    void localAndTestProfilesCanUseEphemeralKeysButEveryAccessChecksActiveSession()
            throws Exception {
        KeyPair keys =
                config.authKeyPair(
                        defaults,
                        new MockEnvironment().withProperty("spring.profiles.active", "test"));
        var transactions = mock(AuthTransactions.class);
        UUID sid = UUID.randomUUID();
        when(transactions.activeSession(sid, 7L)).thenReturn(true);
        var decoder =
                config.jwtDecoder(keys, defaults, transactions, Clock.fixed(now, ZoneOffset.UTC));
        String signed = sign(keys, "7", sid.toString(), "lunepick", now.plusSeconds(1800));
        assertThat(decoder.decode(signed).getSubject()).isEqualTo("7");
        when(transactions.activeSession(sid, 7L)).thenReturn(false);
        assertThatThrownBy(() -> decoder.decode(signed)).isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decoderRejectsExpiredWrongIssuerOrMalformedSessionClaims() throws Exception {
        KeyPair keys =
                config.authKeyPair(
                        defaults,
                        new MockEnvironment().withProperty("spring.profiles.active", "local"));
        var transactions = mock(AuthTransactions.class);
        var decoder =
                config.jwtDecoder(keys, defaults, transactions, Clock.fixed(now, ZoneOffset.UTC));
        UUID sid = UUID.randomUUID();
        when(transactions.activeSession(sid, 7L)).thenReturn(true);
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        sign(
                                                keys,
                                                "7",
                                                sid.toString(),
                                                "lunepick",
                                                now.minusSeconds(1))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        sign(
                                                keys,
                                                "7",
                                                sid.toString(),
                                                "other",
                                                now.plusSeconds(10))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        sign(
                                                keys,
                                                "invalid",
                                                "bad",
                                                "lunepick",
                                                now.plusSeconds(10))))
                .isInstanceOf(JwtValidationException.class);
        assertThatThrownBy(
                        () ->
                                decoder.decode(
                                        sign(
                                                keys,
                                                "0",
                                                sid.toString(),
                                                "lunepick",
                                                now.plusSeconds(10))))
                .isInstanceOf(JwtValidationException.class);
    }

    @Test
    void decoderRequiresConfiguredAudience() throws Exception {
        KeyPair keys =
                config.authKeyPair(
                        defaults,
                        new MockEnvironment().withProperty("spring.profiles.active", "test"));
        var transactions = mock(AuthTransactions.class);
        var decoder =
                config.jwtDecoder(keys, defaults, transactions, Clock.fixed(now, ZoneOffset.UTC));
        for (var audiences : List.of(List.<String>of(), List.of("another-service"))) {
            var claims =
                    JwtClaimsSet.builder()
                            .subject("7")
                            .issuer("lunepick")
                            .audience(audiences)
                            .issuedAt(now)
                            .expiresAt(now.plusSeconds(100))
                            .claim("sid", UUID.randomUUID().toString())
                            .build();
            String signed =
                    config.jwtEncoder(keys)
                            .encode(
                                    JwtEncoderParameters.from(
                                            JwsHeader.with(SignatureAlgorithm.RS256).build(),
                                            claims))
                            .getTokenValue();
            assertThatThrownBy(() -> decoder.decode(signed))
                    .isInstanceOf(JwtValidationException.class);
        }
    }

    private String sign(KeyPair keys, String subject, String sid, String issuer, Instant expiry) {
        var claims =
                JwtClaimsSet.builder()
                        .audience(List.of("lunepick-api"))
                        .subject(subject)
                        .issuer(issuer)
                        .issuedAt(now.minusSeconds(5))
                        .expiresAt(expiry)
                        .claim("sid", sid)
                        .build();
        return config.jwtEncoder(keys)
                .encode(
                        JwtEncoderParameters.from(
                                JwsHeader.with(SignatureAlgorithm.RS256).build(), claims))
                .getTokenValue();
    }
}
