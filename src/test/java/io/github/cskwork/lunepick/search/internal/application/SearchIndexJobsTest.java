package io.github.cskwork.lunepick.search.internal.application;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.catalog.ProductChanged;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

class SearchIndexJobsTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final SearchIndexJobs jobs =
            new SearchIndexJobs(jdbc, Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));

    @Test
    void catalogEventCoalescesThroughThePendingProductConstraint() {
        jobs.on(new ProductChanged(42));
        verify(jdbc)
                .update(
                        ArgumentMatchers.contains(
                                "ON CONFLICT (product_id) WHERE status IN ('QUEUED','RETRY')"),
                        ArgumentMatchers.eq(42L),
                        ArgumentMatchers.any(Timestamp.class),
                        ArgumentMatchers.any(Timestamp.class),
                        ArgumentMatchers.any(Timestamp.class));
    }

    @Test
    void enqueueFailurePropagatesSoPublicationRemainsIncomplete() {
        when(jdbc.update(ArgumentMatchers.anyString(), ArgumentMatchers.any(Object[].class)))
                .thenThrow(new DataAccessResourceFailureException("database unavailable"));
        assertThatThrownBy(() -> jobs.on(new ProductChanged(42)))
                .isInstanceOf(DataAccessResourceFailureException.class);
    }
}
