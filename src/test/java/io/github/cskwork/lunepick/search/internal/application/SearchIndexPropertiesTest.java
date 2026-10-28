package io.github.cskwork.lunepick.search.internal.application;

import static org.assertj.core.api.Assertions.*;

import java.sql.SQLException;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.BadSqlGrammarException;

class SearchIndexPropertiesTest {
    private final SearchIndexProperties properties =
            properties(20, 3, Duration.ofSeconds(1), Duration.ofSeconds(5));

    @Test
    void exponentialBackoffCapsWithoutOverflow() {
        assertThat(properties.backoff(1)).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.backoff(2)).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.backoff(3)).isEqualTo(Duration.ofSeconds(4));
        assertThat(properties.backoff(4)).isEqualTo(Duration.ofSeconds(5));
        assertThat(properties.backoff(Integer.MAX_VALUE)).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void exactAttemptLimitBecomesDead() {
        assertThat(properties.exhausted(2)).isFalse();
        assertThat(properties.exhausted(3)).isTrue();
        assertThat(properties.exhausted(4)).isTrue();
    }

    @Test
    void invalidSettingsFailBeforeScheduling() {
        assertThatThrownBy(() -> properties(0, 3, Duration.ofSeconds(1), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(1001, 3, Duration.ofSeconds(1), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(20, 0, Duration.ofSeconds(1), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(20, 3, Duration.ZERO, Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> properties(20, 3, Duration.ofSeconds(10), Duration.ofSeconds(5)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SearchIndexProperties(
                                        true,
                                        20,
                                        3,
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(5),
                                        Duration.ZERO,
                                        Duration.ofDays(7)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () ->
                                new SearchIndexProperties(
                                        true,
                                        20,
                                        3,
                                        Duration.ofSeconds(1),
                                        Duration.ofSeconds(5),
                                        Duration.ofSeconds(1),
                                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void storedFailureOmitsSecretsAndSqlParameters() {
        assertThat(
                        SearchIndexWorker.safeError(
                                new IllegalStateException("password=private-secret")))
                .isEqualTo("Projection processing failed");
        assertThat(
                        SearchIndexWorker.safeError(
                                new BadSqlGrammarException(
                                        "token=private-secret",
                                        "secret SQL",
                                        new SQLException("secret parameter", "23514"))))
                .isEqualTo("Database error (SQLSTATE 23514)");
        assertThat(
                        SearchIndexWorker.safeError(
                                new BadSqlGrammarException(
                                        "task",
                                        "SQL",
                                        new SQLException("secret parameter", "secret-state"))))
                .isEqualTo("Database error");
    }

    private static SearchIndexProperties properties(
            int size, int attempts, Duration base, Duration max) {
        return new SearchIndexProperties(
                true, size, attempts, base, max, Duration.ofSeconds(1), Duration.ofDays(7));
    }
}
