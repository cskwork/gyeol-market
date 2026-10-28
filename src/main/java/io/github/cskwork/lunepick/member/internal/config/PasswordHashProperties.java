package io.github.cskwork.lunepick.member.internal.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.password")
public record PasswordHashProperties(
        @Min(1) @Max(128) Integer concurrency, @NotNull Duration waitTimeout) {
    public PasswordHashProperties {
        concurrency = concurrency == null ? 8 : concurrency;
        waitTimeout = waitTimeout == null ? Duration.ofSeconds(1) : waitTimeout;
    }

    @AssertTrue(message = "password wait timeout must be between zero and five seconds")
    public boolean isTimeoutValid() {
        return !waitTimeout.isNegative() && waitTimeout.compareTo(Duration.ofSeconds(5)) <= 0;
    }
}
