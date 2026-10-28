package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.reset;

import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.CreateRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.CreateResponse;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.ItemRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.order.internal.persistence.OrderStore;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest(properties = "lunepick.order.expiry.delay=1h")
@ActiveProfiles("test")
class OrderExpiryRecoveryIntegrationTest extends EmbeddedDatabase {
    @Autowired OrderApi orders;
    @Autowired OrderService service;
    @Autowired JdbcTemplate jdbc;
    @Autowired RecoveryClock clock;
    @Autowired PlatformTransactionManager manager;
    @MockitoSpyBean OrderStore store;
    long member;
    long address;

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        RecoveryClock recoveryClock() {
            return new RecoveryClock();
        }
    }

    static class RecoveryClock extends Clock {
        Instant now = Instant.now();

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void fixture() {
        reset(store);
        clock.now = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        jdbc.update("DELETE FROM order_cart_items");
        jdbc.update("DELETE FROM order_items");
        jdbc.update("DELETE FROM order_reconciliation_holds");
        jdbc.update("DELETE FROM orders");
        member =
                jdbc.queryForObject(
                        "INSERT INTO members(email,password_hash,name,created_at) VALUES(?,'unused','expiry-recovery',now()) RETURNING id",
                        Long.class,
                        UUID.randomUUID() + "@expiry.example");
        address =
                jdbc.queryForObject(
                        "INSERT INTO addresses(member_id,recipient_name,phone,zipcode,address_main,is_default) VALUES(?,'복구','01012345678','12345','주소',true) RETURNING id",
                        Long.class,
                        member);
    }

    @Test
    void failingReleaseIsRecordedWithBackoffAndDoesNotBlockTheNextOrder() {
        long badOption = finiteOption();
        long goodOption = finiteOption();
        var bad = create(badOption, 100);
        var good = create(goodOption, 200);
        jdbc.execute(
                "CREATE FUNCTION reject_expiry_release() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN IF NEW.id="
                        + badOption
                        + " AND NEW.available_quantity>OLD.available_quantity THEN RAISE EXCEPTION 'synthetic release failure'; END IF; RETURN NEW; END $$");
        jdbc.execute(
                "CREATE TRIGGER reject_expiry_release BEFORE UPDATE ON product_option FOR EACH ROW EXECUTE FUNCTION reject_expiry_release()");
        try {
            clock.now = clock.now.plus(Duration.ofMinutes(31));
            assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isEqualTo(1);
            assertThat(service.detail(member, bad.orderNo()).status()).isEqualTo("PAYMENT_PENDING");
            assertThat(service.detail(member, good.orderNo()).status()).isEqualTo("CANCELLED");
            assertThat(service.summary(member).pointBalance()).isEqualByComparingTo("4900");
            assertThat(stock(badOption)).isZero();
            assertThat(stock(goodOption)).isEqualTo(1);
            assertFailure(bad, 1, clock.instant().plusSeconds(30));
            assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isZero();
            assertFailure(bad, 1, clock.instant().plusSeconds(30));

            clock.now = clock.now.plusSeconds(30);
            assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isZero();
            assertFailure(bad, 2, clock.instant().plusSeconds(60));
        } finally {
            jdbc.execute("DROP TRIGGER reject_expiry_release ON product_option");
            jdbc.execute("DROP FUNCTION reject_expiry_release()");
        }
        clock.now = clock.now.plusSeconds(60);
        assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isEqualTo(1);
        assertThat(service.detail(member, bad.orderNo()).status()).isEqualTo("CANCELLED");
        assertThat(service.summary(member).pointBalance()).isEqualByComparingTo("5000");
        assertThat(stock(badOption)).isEqualTo(1);
    }

    @Test
    void reconciliationHoldCommitsWithoutWaitingForTheOuterOrderRowAndExcludesExpiry() {
        long heldOption = finiteOption();
        var held = create(heldOption, 100);
        var good = create(finiteOption(), 200);
        new TransactionTemplate(manager)
                .executeWithoutResult(
                        status -> {
                            var target = orders.paymentTarget(held.orderNo());
                            independent()
                                    .executeWithoutResult(
                                            inner ->
                                                    orders.holdForReconciliation(
                                                            target.orderId(),
                                                            "GATEWAY_OUTCOME_UNKNOWN"));
                            status.setRollbackOnly();
                        });
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isEqualTo(1);
        assertThat(service.detail(member, held.orderNo()).status()).isEqualTo("PAYMENT_PENDING");
        assertThat(service.detail(member, good.orderNo()).status()).isEqualTo("CANCELLED");
        assertThat(stock(heldOption)).isZero();
        assertThat(service.summary(member).pointBalance()).isEqualByComparingTo("4900");
    }

    @Test
    void freshHoldCheckProtectsAnOrderWhenHoldCommitsAfterTheClaimSnapshot() {
        var held = create(finiteOption(), 100);
        var good = create(finiteOption(), 200);
        long heldId = service.detail(member, held.orderNo()).id();
        AtomicBoolean inserted = new AtomicBoolean();
        doAnswer(
                        invocation -> {
                            @SuppressWarnings("unchecked")
                            List<OrderStore.Row> rows =
                                    (List<OrderStore.Row>) invocation.callRealMethod();
                            if (!rows.isEmpty()
                                    && rows.getFirst().id() == heldId
                                    && inserted.compareAndSet(false, true)) {
                                independent()
                                        .executeWithoutResult(
                                                status ->
                                                        orders.holdForReconciliation(
                                                                heldId, "LATE_HOLD"));
                            }
                            return rows;
                        })
                .when(store)
                .expiredPending(any(Instant.class), any(Instant.class), eq(1));
        clock.now = clock.now.plus(Duration.ofMinutes(31));
        assertThat(service.expirePending(Duration.ofMinutes(30), 10)).isEqualTo(1);
        assertThat(inserted).isTrue();
        assertThat(service.detail(member, held.orderNo()).status()).isEqualTo("PAYMENT_PENDING");
        assertThat(service.detail(member, good.orderNo()).status()).isEqualTo("CANCELLED");
        assertThat(service.summary(member).pointBalance()).isEqualByComparingTo("4900");
    }

    private TransactionTemplate independent() {
        var template = new TransactionTemplate(manager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template;
    }

    private long finiteOption() {
        return jdbc.queryForObject(
                "INSERT INTO product_option(id,product_id,name,price,status,available_quantity,sort_order) VALUES(nextval('order_payment_key_seq'),1,'recovery',0,'ON_SALE',1,99) RETURNING id",
                Long.class);
    }

    private CreateResponse create(long option, int points) {
        return service.create(
                member,
                UUID.randomUUID().toString(),
                new CreateRequest(
                        List.of(new ItemRequest(option, BigDecimal.ONE)),
                        null,
                        BigDecimal.valueOf(points),
                        address));
    }

    private long stock(long option) {
        return jdbc.queryForObject(
                "SELECT available_quantity FROM product_option WHERE id=?", Long.class, option);
    }

    private void assertFailure(CreateResponse order, int attempts, Instant next) {
        assertThat(
                        jdbc.queryForObject(
                                "SELECT expiry_attempts FROM orders WHERE order_no=?",
                                Integer.class,
                                order.orderNo()))
                .isEqualTo(attempts);
        assertThat(
                        jdbc.queryForObject(
                                        "SELECT expiry_next_attempt_at FROM orders WHERE order_no=?",
                                        OffsetDateTime.class,
                                        order.orderNo())
                                .toInstant())
                .isEqualTo(next);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT expiry_last_error FROM orders WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isNotBlank();
    }
}
