package io.github.cskwork.lunepick.member.internal.application;

import io.github.cskwork.lunepick.member.internal.config.RefreshCleanupProperties;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Keep every generation of an active family so old-token replay still revokes it. */
@Service
@ConditionalOnProperty(
        name = "lunepick.auth.cleanup.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class RefreshCleanup {
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final RefreshCleanupProperties properties;

    public RefreshCleanup(JdbcTemplate jdbc, Clock clock, RefreshCleanupProperties properties) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.properties = properties;
    }

    @Scheduled(
            fixedDelayString = "${lunepick.auth.cleanup.delay:60s}",
            initialDelayString = "${lunepick.auth.cleanup.delay:60s}")
    @Transactional
    public void cleanup() {
        var now = Timestamp.from(clock.instant());
        jdbc.update(
                """
                UPDATE refresh_families SET successor_token=NULL, successor_expires_at=NULL
                WHERE id IN (SELECT id FROM refresh_families WHERE successor_expires_at <= ?
                    ORDER BY successor_expires_at LIMIT ? FOR UPDATE SKIP LOCKED)
                """,
                now,
                properties.batchSize());
        var cutoff = Timestamp.from(clock.instant().minus(properties.retention()));
        var expired =
                jdbc.query(
                        """
                        SELECT id FROM refresh_families WHERE expires_at <= ? OR revoked_at <= ?
                        ORDER BY expires_at LIMIT ? FOR UPDATE SKIP LOCKED
                        """,
                        (rs, row) -> rs.getObject(1, UUID.class),
                        cutoff,
                        cutoff,
                        properties.batchSize());
        for (UUID family : expired) {
            jdbc.update("DELETE FROM refresh_tokens WHERE family_id=?", family);
            jdbc.update("DELETE FROM refresh_families WHERE id=?", family);
        }
    }
}
