package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.member.internal.config.LoginFailureProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The high shared-email threshold supplements the lower per-IP and IP+email budgets. */
@Service
public class LoginFailureGuard {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final LoginFailureProperties properties;

    public LoginFailureGuard(JdbcClient jdbc, Clock clock, LoginFailureProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Transactional(readOnly = true)
    public long retryAfter(String email) {
        Instant now = clock.instant();
        return jdbc.sql(
                        "SELECT blocked_until FROM login_failure_budgets WHERE email_hash=:email AND blocked_until > :now")
                .param("email", digest(normalized(email)))
                .param("now", at(now))
                .query((rs, row) -> rs.getObject("blocked_until", OffsetDateTime.class))
                .optional()
                .filter(until -> until.toInstant().isAfter(now))
                .map(
                        until ->
                                Math.max(
                                        1L,
                                        (Duration.between(now, until.toInstant()).toMillis() + 999)
                                                / 1000))
                .orElse(0L);
    }

    @Transactional
    public void recordFailure(String email, String ip) {
        Instant now = clock.instant();
        String emailHash = digest(normalized(email));
        jdbc.sql(
                        """
                        INSERT INTO login_failure_budgets(email_hash,window_started_at,failures,origin_hashes)
                        VALUES (:email,:now,0,'') ON CONFLICT DO NOTHING
                        """)
                .param("email", emailHash)
                .param("now", at(now))
                .update();
        var budget =
                jdbc.sql(
                                """
                        SELECT window_started_at, failures, blocked_until, origin_hashes
                          FROM login_failure_budgets WHERE email_hash=:email FOR UPDATE
                        """)
                        .param("email", emailHash)
                        .query(
                                (rs, row) ->
                                        new Budget(
                                                rs.getObject(
                                                                "window_started_at",
                                                                OffsetDateTime.class)
                                                        .toInstant(),
                                                rs.getInt("failures"),
                                                rs.getObject("blocked_until", OffsetDateTime.class),
                                                rs.getString("origin_hashes")))
                        .single();
        if (budget.blockedUntil() != null && budget.blockedUntil().toInstant().isAfter(now)) return;
        boolean reset = !budget.startedAt().plus(properties.window()).isAfter(now);
        Instant startedAt = reset ? now : budget.startedAt();
        int failures = reset ? 1 : Math.min(properties.threshold(), budget.failures() + 1);
        List<String> origins =
                new ArrayList<>(reset ? List.of() : budget.origins().lines().toList());
        String origin = digest(ip);
        // Retain only enough distinct origins to prove this is a distributed attack.
        if (!origins.contains(origin) && origins.size() < properties.minimumDistinctIps())
            origins.add(origin);
        Instant blockedUntil =
                failures >= properties.threshold()
                                && origins.size() >= properties.minimumDistinctIps()
                        ? now.plus(properties.cooldown())
                        : null;
        jdbc.sql(
                        """
                        UPDATE login_failure_budgets SET window_started_at=:started,
                            failures=:failures, origin_hashes=:origins, blocked_until=:blocked
                        WHERE email_hash=:email
                        """)
                .param("started", at(startedAt))
                .param("failures", failures)
                .param("origins", String.join("\n", origins))
                .param("blocked", at(blockedUntil))
                .param("email", emailHash)
                .update();
    }

    @Scheduled(
            fixedDelayString = "${lunepick.auth.login-failures.cleanup-delay:5m}",
            initialDelayString = "${lunepick.auth.login-failures.cleanup-delay:5m}")
    @Transactional
    public void cleanup() {
        jdbc.sql(
                        """
                        DELETE FROM login_failure_budgets WHERE email_hash IN (
                          SELECT email_hash FROM login_failure_budgets
                          WHERE window_started_at < :cutoff AND (blocked_until IS NULL OR blocked_until < :now)
                          ORDER BY window_started_at LIMIT 1000 FOR UPDATE SKIP LOCKED)
                        """)
                .param(
                        "cutoff",
                        at(clock.instant().minus(properties.window()).minus(properties.cooldown())))
                .param("now", at(clock.instant()))
                .update();
    }

    private String normalized(String email) {
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private static OffsetDateTime at(Instant instant) {
        return instant == null ? null : instant.atOffset(ZoneOffset.UTC);
    }

    private String digest(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Budget(
            Instant startedAt, int failures, OffsetDateTime blockedUntil, String origins) {}
}
