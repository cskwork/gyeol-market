package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import io.github.cskwork.lunepick.shared.internal.maintenance.OutboxMaintenance;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = {"lunepick.outbox.retry-age=0s", "lunepick.outbox.retry-delay=1h"})
@ActiveProfiles("test")
class OutboxMaintenanceIntegrationTest extends EmbeddedDatabase {
    @Autowired JdbcTemplate jdbc;
    @Autowired TransactionTemplate transactions;
    @Autowired ApplicationEventPublisher events;
    @Autowired OutboxMaintenance maintenance;
    @Autowired Probe probe;
    @Autowired MeterRegistry meters;

    public record DeliveryProbe(UUID id) {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {
        @Bean
        Probe probe(JdbcTemplate jdbc) {
            return new Probe(jdbc);
        }
    }

    static class Probe {
        final AtomicBoolean failing = new AtomicBoolean(true);
        final JdbcTemplate jdbc;

        Probe(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public void recover() {
            failing.set(false);
        }

        public void fail() {
            failing.set(true);
        }

        @ApplicationModuleListener
        public void handle(DeliveryProbe event) {
            if (failing.get())
                throw new IllegalStateException("simulated recoverable listener failure");
            jdbc.update(
                    "INSERT INTO delivery_probe(id) VALUES (?) ON CONFLICT DO NOTHING", event.id());
        }
    }

    @Test
    void scheduledRetryRecoversFailedDeliveryAndDeletesCompletedPublicationsAcrossSchedulers()
            throws Exception {
        jdbc.execute("CREATE TABLE IF NOT EXISTS delivery_probe(id uuid PRIMARY KEY)");
        probe.fail();
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> events.publishEvent(new DeliveryProbe(id)));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM event_publication WHERE event_type=? AND status='FAILED'",
                                                        Integer.class,
                                                        DeliveryProbe.class.getName()))
                                        .isEqualTo(1));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM delivery_probe", Integer.class))
                .isZero();
        probe.recover();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var start = new CountDownLatch(1);
            var first =
                    pool.submit(
                            () -> {
                                start.await();
                                maintenance.resubmit();
                                return true;
                            });
            var second =
                    pool.submit(
                            () -> {
                                start.await();
                                maintenance.resubmit();
                                return true;
                            });
            start.countDown();
            first.get();
            second.get();
        }
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () -> {
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM delivery_probe WHERE id=?",
                                                    Integer.class,
                                                    id))
                                    .isEqualTo(1);
                            assertThat(
                                            jdbc.queryForObject(
                                                    "SELECT count(*) FROM event_publication WHERE event_type=?",
                                                    Integer.class,
                                                    DeliveryProbe.class.getName()))
                                    .isZero();
                        });
    }

    @Test
    void exhaustedPublicationsAreDurablyQuarantinedAndNeverResubmitted() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS delivery_probe(id uuid PRIMARY KEY)");
        probe.fail();
        UUID id = UUID.randomUUID();
        transactions.executeWithoutResult(status -> events.publishEvent(new DeliveryProbe(id)));
        await().atMost(Duration.ofSeconds(10))
                .untilAsserted(
                        () ->
                                assertThat(
                                                jdbc.queryForObject(
                                                        "SELECT count(*) FROM event_publication WHERE event_type=? AND status='FAILED'",
                                                        Integer.class,
                                                        DeliveryProbe.class.getName()))
                                        .isEqualTo(1));
        jdbc.update(
                "UPDATE event_publication SET completion_attempts=5 WHERE event_type=? AND status='FAILED'",
                DeliveryProbe.class.getName());
        double before = meters.get("lunepick.outbox.publications.dead").counter().count();
        maintenance.resubmit();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM event_publication WHERE event_type=?",
                                Integer.class,
                                DeliveryProbe.class.getName()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM event_publication_dead_letters WHERE serialized_event LIKE ?",
                                String.class,
                                "%" + id + "%"))
                .isEqualTo("DEAD");
        assertThat(meters.get("lunepick.outbox.publications.dead").counter().count())
                .isEqualTo(before + 1);
        probe.recover();
        maintenance.resubmit();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM delivery_probe WHERE id=?",
                                Integer.class,
                                id))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT completion_attempts FROM event_publication_dead_letters WHERE serialized_event LIKE ?",
                                Integer.class,
                                "%" + id + "%"))
                .isEqualTo(5);
    }
}
