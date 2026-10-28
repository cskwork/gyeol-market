package io.github.cskwork.lunepick.shared.internal.maintenance;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.modulith.events.FailedEventPublications;
import org.springframework.modulith.events.ResubmissionOptions;
import org.springframework.modulith.events.core.EventPublicationRegistry;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Delivery is at least once; consumers make their business changes idempotently. */
@Component
public class OutboxMaintenance {
    private static final Logger log = LoggerFactory.getLogger(OutboxMaintenance.class);
    // One cluster-wide scheduler selects retry candidates and quarantines exhausted deliveries.
    private static final long RETRY_SCHEDULER_LOCK = 716240119L;
    private final EventPublicationRegistry registry;
    private final FailedEventPublications failed;
    private final OutboxProperties properties;
    private final JdbcClient jdbc;
    private final Clock clock;
    private final Counter dead;

    public OutboxMaintenance(
            EventPublicationRegistry registry,
            FailedEventPublications failed,
            OutboxProperties properties,
            JdbcClient jdbc,
            Clock clock,
            MeterRegistry meters) {
        this.registry = registry;
        this.failed = failed;
        this.properties = properties;
        this.jdbc = jdbc;
        this.clock = clock;
        this.dead = meters.counter("lunepick.outbox.publications.dead");
    }

    @Scheduled(
            fixedDelayString = "${lunepick.outbox.retry-delay:30s}",
            initialDelayString = "${lunepick.outbox.retry-delay:30s}")
    @Transactional
    public void resubmit() {
        // Serialize candidate selection across schedulers without blocking requests or listeners.
        if (!jdbc.sql("SELECT pg_try_advisory_xact_lock(:lock)")
                .param("lock", RETRY_SCHEDULER_LOCK)
                .query(Boolean.class)
                .single()) return;
        // Modulith JDBC v2 atomically claims FAILED -> RESUBMITTED. Competing instances
        // cannot both claim the same publication. Stale claims recover a crashed node.
        registry.markStalePublicationsFailed(status -> properties.staleAfter());
        quarantineExhausted();
        failed.resubmit(
                ResubmissionOptions.defaults()
                        .withMinAge(properties.retryAge())
                        .withBatchSize(properties.batchSize())
                        .withMaxInFlight(properties.maxInFlight())
                        .withFilter(
                                publication ->
                                        publication.getCompletionAttempts()
                                                < properties.maxAttempts()));
    }

    private void quarantineExhausted() {
        List<DeadPublication> publications =
                jdbc.sql(
                                """
                WITH exhausted AS (
                    SELECT id FROM event_publication
                    WHERE completion_date IS NULL AND status='FAILED'
                      AND coalesce(completion_attempts, 0) >= :attempts
                    ORDER BY publication_date, id LIMIT :batch FOR UPDATE SKIP LOCKED
                ), removed AS (
                    DELETE FROM event_publication e USING exhausted x WHERE e.id=x.id RETURNING e.*
                )
                INSERT INTO event_publication_dead_letters
                    (id,listener_id,event_type,serialized_event,publication_date,completion_attempts,
                     last_resubmission_date,failed_at,status,reason)
                SELECT id,listener_id,event_type,serialized_event,publication_date,
                       coalesce(completion_attempts,0),last_resubmission_date,:now,'DEAD',
                       'Resubmission attempt limit reached' FROM removed
                RETURNING id,event_type,listener_id,completion_attempts
                """)
                        .param("attempts", properties.maxAttempts())
                        .param("batch", properties.batchSize())
                        .param("now", Timestamp.from(clock.instant()))
                        .query(DeadPublication.class)
                        .list();
        if (publications.isEmpty()) return;
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        dead.increment(publications.size());
                        for (var publication : publications)
                            log.warn(
                                    "Outbox delivery exhausted: publicationId={} eventType={} listenerId={} attempts={}",
                                    publication.id(),
                                    publication.eventType(),
                                    publication.listenerId(),
                                    publication.completionAttempts());
                    }
                });
    }

    private record DeadPublication(
            UUID id, String eventType, String listenerId, int completionAttempts) {}
}
