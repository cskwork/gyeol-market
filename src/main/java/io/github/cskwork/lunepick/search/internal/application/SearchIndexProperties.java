package io.github.cskwork.lunepick.search.internal.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("lunepick.search-index")
public record SearchIndexProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("20") int batchSize,
        @DefaultValue("3") int maxAttempts,
        @DefaultValue("PT1S") Duration baseBackoff,
        @DefaultValue("PT1M") Duration maxBackoff,
        @DefaultValue("PT1S") Duration delay,
        @DefaultValue("P7D") Duration completedRetention) {
    public SearchIndexProperties {
        if (batchSize < 1 || batchSize > 1000 || maxAttempts < 1) {
            throw new IllegalArgumentException(
                    "Search batch size must be 1..1000 and max attempts positive");
        }
        if (completedRetention == null
                || completedRetention.isNegative()
                || completedRetention.isZero()) {
            throw new IllegalArgumentException("Completed job retention must be positive");
        }
        if (baseBackoff == null
                || maxBackoff == null
                || delay == null
                || baseBackoff.isZero()
                || baseBackoff.isNegative()
                || delay.isZero()
                || delay.isNegative()
                || maxBackoff.compareTo(baseBackoff) < 0) {
            throw new IllegalArgumentException(
                    "Search delays must be positive and max backoff at least base backoff");
        }
    }

    public Duration backoff(int attempts) {
        Duration result = baseBackoff;
        for (int i = 1; i < attempts; i++) {
            if (result.compareTo(maxBackoff.dividedBy(2)) > 0) return maxBackoff;
            result = result.multipliedBy(2);
        }
        return result.compareTo(maxBackoff) > 0 ? maxBackoff : result;
    }

    public boolean exhausted(int attempts) {
        return attempts >= maxAttempts;
    }
}
