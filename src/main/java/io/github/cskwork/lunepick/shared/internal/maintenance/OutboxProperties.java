package io.github.cskwork.lunepick.shared.internal.maintenance;

import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.outbox")
public record OutboxProperties(
        @DefaultValue("30s") Duration retryAge,
        @DefaultValue("5m") Duration staleAfter,
        @DefaultValue("50") @Min(1) int batchSize,
        @DefaultValue("4") @Min(1) int maxInFlight,
        @DefaultValue("5") @Min(1) int maxAttempts) {
    public OutboxProperties {
        if (retryAge == null
                || retryAge.isNegative()
                || staleAfter == null
                || staleAfter.isNegative()
                || staleAfter.isZero())
            throw new IllegalArgumentException(
                    "Outbox durations must be nonnegative and stale-after positive");
    }
}
