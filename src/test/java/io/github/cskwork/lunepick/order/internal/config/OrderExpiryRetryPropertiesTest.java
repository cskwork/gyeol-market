package io.github.cskwork.lunepick.order.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class OrderExpiryRetryPropertiesTest {
    @Test
    void retryDelayGrowsExponentiallyAndIsCapped() {
        var policy = new OrderExpiryRetryProperties(Duration.ofSeconds(30), Duration.ofHours(1));
        assertThat(policy.backoff(1)).isEqualTo(Duration.ofSeconds(30));
        assertThat(policy.backoff(2)).isEqualTo(Duration.ofMinutes(1));
        assertThat(policy.backoff(5)).isEqualTo(Duration.ofMinutes(8));
        assertThat(policy.backoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofHours(1));
    }

    @Test
    void invalidDelaysFailConfigurationImmediately() {
        assertThatThrownBy(
                        () -> new OrderExpiryRetryProperties(Duration.ZERO, Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new OrderExpiryRetryProperties(
                                        Duration.ofSeconds(2), Duration.ofSeconds(1)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
