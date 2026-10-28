package io.github.cskwork.lunepick.member.internal.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class LoginFailurePropertiesTest {
    @Test
    void defaultsRequireADistributedAttackAndKeepCooldownShort() {
        var defaults = new LoginFailureProperties(null, null, null, null);
        assertThat(defaults.threshold()).isEqualTo(100);
        assertThat(defaults.minimumDistinctIps()).isEqualTo(3);
        assertThat(defaults.window()).isEqualTo(Duration.ofMinutes(1));
        assertThat(defaults.cooldown()).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void invalidBudgetsFailFast() {
        assertThatThrownBy(() -> new LoginFailureProperties(1, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginFailureProperties(null, Duration.ZERO, null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> new LoginFailureProperties(null, null, Duration.ofMinutes(2), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoginFailureProperties(null, null, null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
