package io.github.cskwork.lunepick.search.internal.application;

import io.github.cskwork.lunepick.catalog.ProductChanged;
import java.sql.Timestamp;
import java.time.Clock;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class SearchIndexJobs {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    public SearchIndexJobs(JdbcTemplate jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    // Modulith persists the publication in the publisher transaction and completes it only after
    // this commit.
    @ApplicationModuleListener
    public void on(ProductChanged event) {
        enqueue(event.productId());
    }

    @Transactional
    public void enqueue(long productId) {
        var now = Timestamp.from(clock.instant());
        jdbc.update(
                "INSERT INTO search_index_jobs(product_id,next_attempt_at,created_at,updated_at)"
                        + " VALUES (?,?,?,?)"
                        + " ON CONFLICT (product_id) WHERE status IN ('QUEUED','RETRY')"
                        + " DO UPDATE SET status='QUEUED', attempts=0, last_error=NULL,"
                        + " next_attempt_at=excluded.next_attempt_at, updated_at=excluded.updated_at",
                productId,
                now,
                now,
                now);
    }
}
