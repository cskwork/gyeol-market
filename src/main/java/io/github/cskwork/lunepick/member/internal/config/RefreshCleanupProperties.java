package io.github.cskwork.lunepick.member.internal.config;

import jakarta.validation.constraints.*;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.auth.cleanup")
public record RefreshCleanupProperties(
        @Min(1) @Max(5000) Integer batchSize, @NotNull Duration retention) {
    public RefreshCleanupProperties {
        batchSize = batchSize == null ? 500 : batchSize;
        retention = retention == null ? Duration.ofDays(1) : retention;
    }

    @AssertTrue(message = "refresh cleanup retention must not be negative")
    public boolean isRetentionValid() {
        return !retention.isNegative();
    }
}
