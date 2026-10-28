package io.github.cskwork.lunepick.search.internal.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@EnableConfigurationProperties(SearchIndexProperties.class)
public class SearchIndexWorker {
    private final JdbcTemplate jdbc;
    private final SearchProjection projection;
    private final SearchIndexProperties properties;
    private final Clock clock;
    private final TransactionTemplate transaction;
    private final TransactionTemplate projectionWrite;
    private final TransactionTemplate catalogRead;
    private final Counter processed;
    private final Counter failed;
    private volatile Map<String, Double> queueCounts = Map.of();

    public SearchIndexWorker(
            JdbcTemplate jdbc,
            SearchProjection projection,
            SearchIndexProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.jdbc = jdbc;
        this.projection = projection;
        this.properties = properties;
        this.clock = clock;
        // The queue and projection are JDBC-only. Spring's nested transaction restores a failed
        // PostgreSQL statement while retaining the claim lock for retry-state persistence.
        var manager = new JdbcTransactionManager(Objects.requireNonNull(jdbc.getDataSource()));
        transaction = new TransactionTemplate(manager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        projectionWrite = new TransactionTemplate(manager);
        projectionWrite.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
        catalogRead = new TransactionTemplate(manager);
        // Catalog owns its JPA transaction; suspend the JDBC claim connection for that read.
        catalogRead.setPropagationBehavior(TransactionDefinition.PROPAGATION_NOT_SUPPORTED);
        processed = meters.counter("lunepick.search.index.processed");
        failed = meters.counter("lunepick.search.index.failed");
        for (String status : List.of("QUEUED", "RETRY", "DEAD")) {
            Gauge.builder(
                            "lunepick.search.index.jobs",
                            this,
                            worker -> worker.queueCounts.getOrDefault(status, Double.NaN))
                    .tag("status", status.toLowerCase(Locale.ROOT))
                    .register(meters);
        }
    }

    @Scheduled(fixedDelayString = "${lunepick.search-index.delay:PT1S}")
    public void scheduled() {
        if (properties.enabled()) processBatch();
    }

    /** Each claim, projection write and completion commits in its own bounded transaction. */
    public int processBatch() {
        int attempted = 0;
        while (attempted < properties.batchSize()) {
            if (!Boolean.TRUE.equals(transaction.execute(status -> processOne()))) break;
            attempted++;
        }
        return attempted;
    }

    private boolean processOne() {
        List<Job> jobs =
                jdbc.query(
                        """
            SELECT id, product_id, attempts FROM search_index_jobs
            WHERE status IN ('QUEUED', 'RETRY') AND next_attempt_at <= ?
            ORDER BY next_attempt_at, id LIMIT 1 FOR UPDATE SKIP LOCKED
            """,
                        (rs, row) ->
                                new Job(
                                        rs.getLong("id"),
                                        rs.getLong("product_id"),
                                        rs.getInt("attempts")),
                        Timestamp.from(clock.instant()));
        if (jobs.isEmpty()) return false;
        Job job = jobs.getFirst();
        int attempts = job.attempts() + 1;
        try {
            var product = catalogRead.execute(status -> projection.product(job.productId()));
            if (product == null) throw new IllegalStateException("Catalog returned no result");
            projectionWrite.executeWithoutResult(
                    status -> projection.update(job.productId(), product));
        } catch (RuntimeException failure) {
            jdbc.update(
                    """
          UPDATE search_index_jobs SET status=?, attempts=?, next_attempt_at=?, last_error=?, updated_at=?
          WHERE id=?
          """,
                    properties.exhausted(attempts) ? "DEAD" : "RETRY",
                    attempts,
                    Timestamp.from(clock.instant().plus(properties.backoff(attempts))),
                    safeError(failure),
                    Timestamp.from(clock.instant()),
                    job.id());
            afterCommit(failed);
            return true;
        }
        jdbc.update(
                """
        UPDATE search_index_jobs SET status='COMPLETED', attempts=?, last_error=NULL, updated_at=? WHERE id=?
        """,
                attempts,
                Timestamp.from(clock.instant()),
                job.id());
        afterCommit(processed);
        return true;
    }

    @Scheduled(fixedDelayString = "${lunepick.search-index.metrics-delay:PT30S}")
    public void refreshMetrics() {
        try {
            var counts = new HashMap<String, Double>();
            jdbc.query(
                    "SELECT status, count(*) AS count FROM search_index_jobs WHERE status IN"
                            + " ('QUEUED','RETRY','DEAD') GROUP BY status",
                    rs -> {
                        counts.put(rs.getString("status"), rs.getDouble("count"));
                    });
            for (String status : List.of("QUEUED", "RETRY", "DEAD"))
                counts.putIfAbsent(status, 0.0);
            queueCounts = Map.copyOf(counts);
        } catch (DataAccessException unavailable) {
            queueCounts = Map.of();
        }
    }

    @Scheduled(fixedDelayString = "${lunepick.search-index.cleanup-delay:PT1H}")
    public void cleanCompleted() {
        jdbc.update(
                "DELETE FROM search_index_jobs WHERE status='COMPLETED' AND updated_at < ?",
                Timestamp.from(clock.instant().minus(properties.completedRetention())));
    }

    static String safeError(RuntimeException failure) {
        // Never persist exception messages, SQL parameters, credentials or customer data.
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql) {
                String state = sql.getSQLState();
                return "Database error"
                        + (state != null && state.matches("[A-Z0-9]{5}")
                                ? " (SQLSTATE " + state + ")"
                                : "");
            }
        }
        return "Projection processing failed";
    }

    private void afterCommit(Counter counter) {
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        counter.increment();
                    }
                });
    }

    private record Job(long id, long productId, int attempts) {}
}
