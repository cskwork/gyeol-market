package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.member.internal.application.RefreshCleanup;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        properties = {"lunepick.auth.cleanup.enabled=true", "lunepick.auth.cleanup.delay=1d"})
@ActiveProfiles("test")
class RefreshCleanupIntegrationTest extends EmbeddedDatabase {
    @Autowired RefreshCleanup cleanup;
    @Autowired JdbcTemplate jdbc;
    @Autowired Clock clock;
    @Autowired DataSource dataSource;

    UUID family(Duration expiry, Duration revocation) {
        UUID id = UUID.randomUUID();
        var now = clock.instant();
        Long member =
                jdbc.queryForObject(
                        "SELECT id FROM members WHERE email='demo@example.com'", Long.class);
        jdbc.update(
                "INSERT INTO refresh_families(id,member_id,expires_at,revoked_at,created_at) VALUES (?,?,?,?,?)",
                id,
                member,
                Timestamp.from(now.plus(expiry)),
                revocation == null ? null : Timestamp.from(now.plus(revocation)),
                Timestamp.from(now.minus(Duration.ofDays(3))));
        jdbc.update(
                "INSERT INTO refresh_tokens(family_id,token_hash,expires_at,consumed_at,created_at) VALUES (?,?,?,?,?)",
                id,
                UUID.randomUUID().toString().replace("-", "").repeat(2),
                Timestamp.from(now.plus(expiry)),
                Timestamp.from(now.minus(Duration.ofDays(2))),
                Timestamp.from(now.minus(Duration.ofDays(3))));
        return id;
    }

    long count(String table, String column, UUID id) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM " + table + " WHERE " + column + "=?", Long.class, id);
    }

    @Test
    void cleanupDeletesWholeOldFamiliesAndRetainsActiveReplayEvidenceAndRecentRevocations() {
        UUID active = family(Duration.ofDays(1), null);
        UUID expired = family(Duration.ofDays(-2), null);
        UUID revoked = family(Duration.ofDays(1), Duration.ofDays(-2));
        UUID recent = family(Duration.ofDays(1), Duration.ofHours(-1));
        jdbc.update(
                "UPDATE refresh_families SET successor_token='encrypted-value',successor_expires_at=? WHERE id=?",
                Timestamp.from(clock.instant().minusSeconds(1)),
                active);
        cleanup.cleanup();
        for (UUID id : List.of(expired, revoked)) {
            assertThat(count("refresh_families", "id", id)).isZero();
            assertThat(count("refresh_tokens", "family_id", id)).isZero();
        }
        for (UUID id : List.of(active, recent)) {
            assertThat(count("refresh_families", "id", id)).isOne();
            assertThat(count("refresh_tokens", "family_id", id)).isOne();
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT successor_token FROM refresh_families WHERE id=?",
                                String.class,
                                active))
                .isNull();
    }

    @Test
    void cleanupSkipsFamilyLockedByConcurrentRotation() throws Exception {
        UUID expired = family(Duration.ofDays(-2), null);
        try (var connection = dataSource.getConnection()) {
            connection.setAutoCommit(false);
            try (var statement =
                    connection.prepareStatement(
                            "SELECT id FROM refresh_families WHERE id=? FOR UPDATE")) {
                statement.setObject(1, expired);
                statement.executeQuery().close();
            }
            cleanup.cleanup();
            assertThat(count("refresh_families", "id", expired)).isOne();
            connection.rollback();
        }
        cleanup.cleanup();
        assertThat(count("refresh_families", "id", expired)).isZero();
    }
}
