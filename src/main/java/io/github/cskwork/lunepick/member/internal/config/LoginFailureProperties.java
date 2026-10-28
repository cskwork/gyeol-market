package io.github.cskwork.lunepick.member.internal.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("lunepick.auth.login-failures")
public record LoginFailureProperties(
        Integer threshold, Duration window, Duration cooldown, Integer minimumDistinctIps) {
    public LoginFailureProperties {
        threshold = threshold == null ? 100 : threshold;
        window = window == null ? Duration.ofMinutes(1) : window;
        cooldown = cooldown == null ? Duration.ofSeconds(30) : cooldown;
        minimumDistinctIps = minimumDistinctIps == null ? 3 : minimumDistinctIps;
        if (minimumDistinctIps < 2
                || minimumDistinctIps > 20
                || threshold < minimumDistinctIps
                || threshold > 10_000
                || window.isNegative()
                || window.isZero()
                || cooldown.isNegative()
                || cooldown.isZero()
                || cooldown.compareTo(Duration.ofMinutes(1)) > 0)
            throw new IllegalArgumentException(
                    "Login failure budgets need threshold >= distinct IPs (2..20), a positive window and cooldown <= 1 minute");
    }
}
