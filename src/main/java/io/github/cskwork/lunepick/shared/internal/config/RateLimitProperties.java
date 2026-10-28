package io.github.cskwork.lunepick.shared.internal.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.rate-limit")
public record RateLimitProperties(Boolean enabled, @NotNull Duration window) {
    public RateLimitProperties {
        enabled = enabled == null || enabled;
        window = window == null ? Duration.ofMinutes(1) : window;
    }

    @AssertTrue(message = "rate limit window must be positive and at most one day")
    public boolean isWindowValid() {
        return !window.isNegative()
                && !window.isZero()
                && window.compareTo(Duration.ofDays(1)) <= 0;
    }
}
