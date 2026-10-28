package io.github.cskwork.lunepick.order.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lunepick.order.expiry.retry")
public record OrderExpiryRetryProperties(
        @DefaultValue("30s") Duration delay, @DefaultValue("1h") Duration maxDelay) {
    public OrderExpiryRetryProperties {
        if (delay == null
                || delay.isNegative()
                || delay.isZero()
                || maxDelay == null
                || maxDelay.compareTo(delay) < 0)
            throw new IllegalArgumentException(
                    "Expiry retry delay must be positive and max-delay >= delay");
    }

    public Duration backoff(int attempts) {
        Duration result = delay;
        for (int i = 1; i < attempts && result.compareTo(maxDelay) < 0; i++) {
            try {
                result = result.multipliedBy(2);
            } catch (ArithmeticException overflow) {
                return maxDelay;
            }
        }
        return result.compareTo(maxDelay) > 0 ? maxDelay : result;
    }
}
