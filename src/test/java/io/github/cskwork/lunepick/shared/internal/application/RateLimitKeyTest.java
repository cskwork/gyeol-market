package io.github.cskwork.lunepick.shared.internal.application;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.shared.internal.config.RateLimitProperties;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RateLimitKeyTest {
    @Test
    void instanceIndependentKeysSeparateScopesAndIdentities() {
        assertThat(RateLimitService.key("login-email", "fixture@example.test"))
                .isEqualTo(RateLimitService.key("login-email", "fixture@example.test"))
                .isNotEqualTo(RateLimitService.key("signup-ip", "fixture@example.test"))
                .isNotEqualTo(RateLimitService.key("login-email", "other@example.test"));
    }

    @Test
    void rateWindowsAreValidated() {
        assertThat(new RateLimitProperties(null, null).isWindowValid()).isTrue();
        assertThat(new RateLimitProperties(true, Duration.ZERO).isWindowValid()).isFalse();
        assertThat(new RateLimitProperties(true, Duration.ofDays(2)).isWindowValid()).isFalse();
    }
}
