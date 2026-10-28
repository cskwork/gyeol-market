package io.github.cskwork.lunepick.member.internal.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.core.io.Resource;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.auth")
public record AuthProperties(
        @NotBlank String issuer,
        @NotNull Duration accessTtl,
        @NotNull Duration refreshTtl,
        Resource publicKey,
        Resource privateKey,
        @NotBlank String audience,
        @NotNull Duration refreshReuseGrace) {
    @ConstructorBinding
    public AuthProperties {
        issuer = issuer == null ? "lunepick" : issuer;
        audience = audience == null ? "lunepick-api" : audience;
        refreshReuseGrace = refreshReuseGrace == null ? Duration.ofSeconds(3) : refreshReuseGrace;
        accessTtl = accessTtl == null ? Duration.ofMinutes(30) : accessTtl;
        refreshTtl = refreshTtl == null ? Duration.ofDays(30) : refreshTtl;
    }

    public AuthProperties(
            String issuer,
            Duration accessTtl,
            Duration refreshTtl,
            Resource publicKey,
            Resource privateKey) {
        this(issuer, accessTtl, refreshTtl, publicKey, privateKey, null, null);
    }

    @AssertTrue(message = "refresh reuse grace must be between zero and ten seconds")
    public boolean isReuseGraceValid() {
        return !refreshReuseGrace.isNegative()
                && refreshReuseGrace.compareTo(Duration.ofSeconds(10)) <= 0;
    }

    @AssertTrue(message = "token TTLs must be positive, access <= 1 hour, and refresh >= access")
    public boolean isTokenLifetimeValid() {
        return accessTtl != null
                && refreshTtl != null
                && !accessTtl.isNegative()
                && !accessTtl.isZero()
                && accessTtl.compareTo(Duration.ofHours(1)) <= 0
                && refreshTtl.compareTo(accessTtl) >= 0;
    }
}
