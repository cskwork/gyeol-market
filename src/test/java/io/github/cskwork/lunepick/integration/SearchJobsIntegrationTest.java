package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.catalog.ProductChanged;
import io.github.cskwork.lunepick.search.internal.application.SearchIndexJobs;
import io.github.cskwork.lunepick.search.internal.application.SearchIndexWorker;
import io.github.cskwork.lunepick.search.internal.application.SearchProjection;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(
        properties = {
            "lunepick.search-index.batch-size=1",
            "lunepick.search-index.max-attempts=3",
            "lunepick.search-index.base-backoff=PT1S",
            "lunepick.search-index.max-backoff=PT2S"
        })
@ActiveProfiles("test")
class SearchJobsIntegrationTest extends EmbeddedDatabase {
    @Autowired JdbcTemplate jdbc;
    @Autowired SearchIndexJobs jobs;
    @Autowired SearchIndexWorker worker;
    @Autowired SearchProjection projection;
    @Autowired ApplicationEventPublisher events;
    @Autowired TransactionTemplate transactions;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void clearQueue() {
        jdbc.update("DELETE FROM search_index_jobs");
    }

    @AfterEach
    void cleanUp() {
        jdbc.execute("DROP TRIGGER IF EXISTS test_search_write ON search_documents");
        jdbc.execute("DROP FUNCTION IF EXISTS test_search_write()");
        jdbc.execute("DROP TRIGGER IF EXISTS test_search_completion ON search_index_jobs");
        jdbc.execute("DROP FUNCTION IF EXISTS test_search_completion()");
        jdbc.update("DELETE FROM search_index_jobs");
    }

    @Test
    void publicationEnqueuesAfterCommitAndNeverOnRollback() throws Exception {
        long rolledBackProduct = 889001;
        transactions.executeWithoutResult(
                status -> {
                    events.publishEvent(new ProductChanged(rolledBackProduct));
                    assertThat(queueCount(rolledBackProduct)).isZero();
                    status.setRollbackOnly();
                });
        assertThat(queueCount(rolledBackProduct)).isZero();

        long committedProduct = 889002;
        transactions.executeWithoutResult(
                status -> {
                    events.publishEvent(new ProductChanged(committedProduct));
                    assertThat(queueCount(committedProduct)).isZero();
                });
        await(() -> queueCount(committedProduct) == 1);
        assertThat(queueCount(rolledBackProduct)).isZero();
        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(status(committedProduct)).isEqualTo("COMPLETED");
    }

    @Test
    void disabledSchedulerStillAllowsManualProcessingAndRefreshesAndRemovesProjection() {
        String original = jdbc.queryForObject("SELECT name FROM product WHERE id=13", String.class);
        try {
            jdbc.update("UPDATE product SET name='async-search-job-marker' WHERE id=13");
            jobs.enqueue(13);
            worker.scheduled();
            assertThat(status(13)).isEqualTo("QUEUED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo(original);
            assertThat(worker.processBatch()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo("async-search-job-marker");
            assertThat(status(13)).isEqualTo("COMPLETED");

            jdbc.update(
                    """
          INSERT INTO search_documents(product_id,brand_id,brand_name,product_name,description,sale_price,
              original_price,discount_rate,category_ids,created_order,rating,review_count)
          VALUES(889003,1,'stale','stale','',1,1,0,'{}',0,0,0)
          """);
            jobs.enqueue(889003);
            worker.processBatch();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM search_documents WHERE product_id=889003",
                                    Integer.class))
                    .isZero();
        } finally {
            jdbc.update("UPDATE product SET name=? WHERE id=13", original);
            jobs.enqueue(13);
            worker.processBatch();
        }
    }

    @Test
    void sqlFailureRollsBackProjectionButCommitsExponentialRetriesAndDeadLetter() {
        jdbc.execute(
                """
        CREATE FUNCTION test_search_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN RAISE EXCEPTION 'token=never-store-this' USING ERRCODE='23514'; END $$
        """);
        jdbc.execute(
                "CREATE TRIGGER test_search_write BEFORE INSERT OR UPDATE ON search_documents FOR"
                        + " EACH ROW EXECUTE FUNCTION test_search_write()");
        jobs.enqueue(13);
        double failedBefore = meters.get("lunepick.search.index.failed").counter().count();
        for (int attempt = 1; attempt <= 3; attempt++) {
            Instant before = Instant.now();
            assertThat(worker.processBatch()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempts FROM search_index_jobs WHERE product_id=13",
                                    Integer.class))
                    .isEqualTo(attempt);
            assertThat(status(13)).isEqualTo(attempt == 3 ? "DEAD" : "RETRY");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_error FROM search_index_jobs WHERE product_id=13",
                                    String.class))
                    .isEqualTo("Database error (SQLSTATE 23514)")
                    .doesNotContain("token", "never-store-this");
            Instant next =
                    jdbc.queryForObject(
                            "SELECT next_attempt_at FROM search_index_jobs WHERE product_id=13",
                            (rs, row) -> rs.getTimestamp(1).toInstant());
            assertThat(next).isAfterOrEqualTo(before.plusSeconds(attempt == 1 ? 1 : 2));
            assertThat(worker.processBatch()).isZero();
            jdbc.update(
                    "UPDATE search_index_jobs SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1"
                            + " second' WHERE product_id=13");
        }
        assertThat(worker.processBatch()).isZero();
        assertThat(meters.get("lunepick.search.index.failed").counter().count())
                .isEqualTo(failedBefore + 3);
        worker.refreshMetrics();
        assertThat(meters.get("lunepick.search.index.jobs").tag("status", "dead").gauge().value())
                .isEqualTo(1);
        assertThat(meters.get("lunepick.search.index.jobs").tag("status", "retry").gauge().value())
                .isZero();
    }

    @Test
    void catalogTransactionFailureRetriesWithoutPoisoningClaimTransaction() {
        jobs.enqueue(13);
        // Cause an actual JPA catalog read failure, including its transaction interceptor's
        // rollback-only path.
        jdbc.execute("ALTER TABLE product_review RENAME TO test_search_product_review");
        try {
            assertThat(worker.processBatch()).isEqualTo(1);
            assertThat(status(13)).isEqualTo("RETRY");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT last_error FROM search_index_jobs WHERE product_id=13",
                                    String.class))
                    .isEqualTo("Database error (SQLSTATE 42P01)");
        } finally {
            jdbc.execute("ALTER TABLE test_search_product_review RENAME TO product_review");
        }
        jdbc.update(
                "UPDATE search_index_jobs SET next_attempt_at=CURRENT_TIMESTAMP-INTERVAL '1 second'"
                        + " WHERE product_id=13");
        assertThat(worker.processBatch()).isEqualTo(1);
        assertThat(status(13)).isEqualTo("COMPLETED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT attempts FROM search_index_jobs WHERE product_id=13",
                                Integer.class))
                .isEqualTo(2);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT last_error FROM search_index_jobs WHERE product_id=13",
                                String.class))
                .isNull();
    }

    @Test
    void failedCompletionRollsBackProjectionAndClaimTogether() {
        String original = jdbc.queryForObject("SELECT name FROM product WHERE id=13", String.class);
        jdbc.execute(
                """
        CREATE FUNCTION test_search_completion() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.status='COMPLETED' THEN RAISE EXCEPTION 'completion unavailable'; END IF; RETURN NEW; END $$
        """);
        jdbc.execute(
                "CREATE TRIGGER test_search_completion BEFORE UPDATE ON search_index_jobs FOR EACH"
                        + " ROW EXECUTE FUNCTION test_search_completion()");
        try {
            jdbc.update("UPDATE product SET name='atomic-search-marker' WHERE id=13");
            jobs.enqueue(13);
            assertThatThrownBy(worker::processBatch).isInstanceOf(DataAccessException.class);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo(original);
            assertThat(status(13)).isEqualTo("QUEUED");
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT attempts FROM search_index_jobs WHERE product_id=13",
                                    Integer.class))
                    .isZero();
            jdbc.execute("DROP TRIGGER test_search_completion ON search_index_jobs");
            assertThat(worker.processBatch()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo("atomic-search-marker");
        } finally {
            jdbc.execute("DROP TRIGGER IF EXISTS test_search_completion ON search_index_jobs");
            jdbc.update("UPDATE product SET name=? WHERE id=13", original);
            jobs.enqueue(13);
            worker.processBatch();
        }
    }

    @Test
    void concurrentWorkerSkipsLockedClaimAndNeverProcessesSameJobTwice() throws Exception {
        jdbc.execute(
                """
        CREATE FUNCTION test_search_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN IF NEW.product_id=13 THEN PERFORM pg_advisory_xact_lock(478311); END IF; RETURN NEW; END $$
        """);
        jdbc.execute(
                "CREATE TRIGGER test_search_write BEFORE INSERT OR UPDATE ON search_documents FOR"
                        + " EACH ROW EXECUTE FUNCTION test_search_write()");
        jobs.enqueue(13);
        jobs.enqueue(889004);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("SELECT pg_advisory_xact_lock(478311)");
            Future<Integer> first = executor.submit(worker::processBatch);
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            """
                      SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND objid=478311 AND NOT granted)
                      """,
                                            Boolean.class)));
            Future<Integer> second = executor.submit(worker::processBatch);
            assertThat(second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(status(889004)).isEqualTo("COMPLETED");
            assertThat(status(13)).isEqualTo("QUEUED");
            blocker.commit();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            assertThat(status(13)).isEqualTo("COMPLETED");
            assertThat(
                            jdbc.queryForList(
                                    "SELECT attempts FROM search_index_jobs ORDER BY id",
                                    Integer.class))
                    .containsExactly(1, 1);
            assertThat(worker.processBatch()).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void pendingChangesCoalesceAndChangeDuringProcessingIsNotLost() throws Exception {
        String original = jdbc.queryForObject("SELECT name FROM product WHERE id=13", String.class);
        jdbc.execute(
                """
        CREATE FUNCTION test_search_write() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            IF NEW.product_id=13 AND NEW.product_name='search-snapshot-a' THEN
                PERFORM pg_advisory_xact_lock(478312);
            END IF;
            RETURN NEW;
        END $$
        """);
        jdbc.execute(
                "CREATE TRIGGER test_search_write BEFORE INSERT OR UPDATE ON search_documents FOR EACH ROW"
                        + " EXECUTE FUNCTION test_search_write()");
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try (Connection blocker = dataSource.getConnection()) {
            blocker.setAutoCommit(false);
            blocker.createStatement().execute("SELECT pg_advisory_xact_lock(478312)");
            jdbc.update("UPDATE product SET name='search-snapshot-a' WHERE id=13");
            jobs.enqueue(13);
            jobs.enqueue(13);
            assertThat(queueCount(13)).isEqualTo(1);
            Future<Integer> first = executor.submit(worker::processBatch);
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM pg_locks WHERE locktype='advisory' AND"
                                                    + " objid=478312 AND NOT granted)",
                                            Boolean.class)));
            jdbc.update("UPDATE product SET name='search-snapshot-b' WHERE id=13");
            Future<?> newerChange = executor.submit(() -> jobs.enqueue(13));
            await(
                    () ->
                            Boolean.TRUE.equals(
                                    jdbc.queryForObject(
                                            "SELECT EXISTS(SELECT 1 FROM pg_stat_activity WHERE query LIKE '%INSERT INTO"
                                                    + " search_index_jobs%' AND wait_event_type='Lock')",
                                            Boolean.class)));
            // A second worker skips the one pending row rather than reading a stale snapshot.
            assertThat(worker.processBatch()).isZero();
            blocker.commit();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(1);
            newerChange.get(5, TimeUnit.SECONDS);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM search_index_jobs WHERE product_id=13 AND status IN"
                                            + " ('QUEUED','RETRY')",
                                    Integer.class))
                    .isEqualTo(1);
            assertThat(worker.processBatch()).isEqualTo(1);
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo("search-snapshot-b");
            assertThat(worker.processBatch()).isZero();
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            jdbc.execute("DROP TRIGGER IF EXISTS test_search_write ON search_documents");
            jdbc.update("UPDATE product SET name=? WHERE id=13", original);
            jobs.enqueue(13);
            worker.processBatch();
        }
    }

    @Test
    void startupDoesNotRebuildAnExistingProjection() {
        String original = jdbc.queryForObject("SELECT name FROM product WHERE id=13", String.class);
        try {
            jdbc.update("UPDATE product SET name='startup-should-not-replace-index' WHERE id=13");
            projection.rebuild();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo(original);
            jdbc.update("DELETE FROM search_documents");
            projection.rebuild();
            assertThat(
                            jdbc.queryForObject(
                                    "SELECT product_name FROM search_documents WHERE product_id=13",
                                    String.class))
                    .isEqualTo("startup-should-not-replace-index");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM search_documents", Integer.class))
                    .isEqualTo(
                            jdbc.queryForObject(
                                    "SELECT count(*) FROM product WHERE status <> 'HIDDEN'",
                                    Integer.class));
        } finally {
            jdbc.update("UPDATE product SET name=? WHERE id=13", original);
            jobs.enqueue(13);
            worker.processBatch();
        }
    }

    @Test
    void retentionDeletesOnlyOldCompletedJobsAndMetricsAreCachedBetweenRefreshes() {
        jobs.enqueue(889100);
        jobs.enqueue(889101);
        jobs.enqueue(889102);
        jobs.enqueue(889103);
        jdbc.update(
                "UPDATE search_index_jobs SET status='COMPLETED', updated_at=CURRENT_TIMESTAMP-INTERVAL '8"
                        + " days' WHERE product_id=889100");
        jdbc.update("UPDATE search_index_jobs SET status='COMPLETED' WHERE product_id=889101");
        jdbc.update(
                "UPDATE search_index_jobs SET status='DEAD', updated_at=CURRENT_TIMESTAMP-INTERVAL '8 days'"
                        + " WHERE product_id=889102");
        worker.refreshMetrics();
        var gauge = meters.get("lunepick.search.index.jobs").tag("status", "queued").gauge();
        assertThat(gauge.value()).isEqualTo(1);
        jobs.enqueue(889104);
        assertThat(gauge.value()).isEqualTo(1);
        worker.refreshMetrics();
        assertThat(gauge.value()).isEqualTo(2);
        worker.cleanCompleted();
        assertThat(queueCount(889100)).isZero();
        assertThat(queueCount(889101)).isEqualTo(1);
        assertThat(queueCount(889102)).isEqualTo(1);
        assertThat(queueCount(889103)).isEqualTo(1);
    }

    private int queueCount(long productId) {
        Integer count =
                jdbc.queryForObject(
                        "SELECT count(*) FROM search_index_jobs WHERE product_id=?",
                        Integer.class,
                        productId);
        return count == null ? 0 : count;
    }

    private String status(long productId) {
        return jdbc.queryForObject(
                "SELECT status FROM search_index_jobs WHERE product_id=? ORDER BY id DESC LIMIT 1",
                String.class,
                productId);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(5));
        while (!condition.getAsBoolean() && Instant.now().isBefore(deadline)) Thread.sleep(20);
        assertThat(condition.getAsBoolean())
                .as("asynchronous condition completed within five seconds")
                .isTrue();
    }
}
