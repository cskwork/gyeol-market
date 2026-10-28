package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import io.github.cskwork.lunepick.catalog.CatalogStockApi;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.CreateRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.CreateResponse;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.ItemRequest;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
class UnlimitedStockConcurrencyIntegrationTest extends EmbeddedDatabase {
    private static final long PRODUCT = 990901;
    private static final long OPTION = 990901;
    @Autowired JdbcTemplate jdbc;
    @Autowired OrderService orders;
    @Autowired CatalogStockApi stock;
    @Autowired PlatformTransactionManager transactionManager;
    private long member;
    private long address;

    @BeforeEach
    void fixture() {
        jdbc.update(
                """
                INSERT INTO product(id,brand_id,name,description,base_price,sale_price,created_order,status)
                VALUES (?,1,'무제한 상품','동시 상태 검증',10000,10000,990901,'ON_SALE')
                ON CONFLICT(id) DO UPDATE SET status='ON_SALE'
                """,
                PRODUCT);
        jdbc.update(
                """
                INSERT INTO product_option(id,product_id,name,price,status,available_quantity,sort_order)
                VALUES (?,?,'무제한 옵션',0,'ON_SALE',NULL,1)
                ON CONFLICT(id) DO UPDATE SET status='ON_SALE',available_quantity=NULL
                """,
                OPTION,
                PRODUCT);
        member =
                jdbc.queryForObject(
                        "INSERT INTO members(email,password_hash,name,created_at) VALUES (?,'unused','stock member',now()) RETURNING id",
                        Long.class,
                        UUID.randomUUID() + "@stock.example.test");
        address =
                jdbc.queryForObject(
                        "INSERT INTO addresses(member_id,recipient_name,phone,zipcode,address_main,is_default) VALUES (?,'수령인','01000000000','12345','주소',true) RETURNING id",
                        Long.class,
                        member);
    }

    @ParameterizedTest
    @CsvSource({"OPTION,SOLD_OUT", "OPTION,HIDDEN", "PRODUCT,HIDDEN"})
    void concurrentStatusCommitRejectsUnlimitedReservationAndRollsBackCheckout(
            String target, String changedStatus) throws Exception {
        var workerPid = new AtomicInteger();
        var started = new CountDownLatch(1);
        var reservation = new AtomicReference<Future<CreateResponse>>();
        try (var pool = Executors.newSingleThreadExecutor()) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                updateStatus(target, changedStatus);
                                reservation.set(
                                        pool.submit(
                                                () ->
                                                        new TransactionTemplate(transactionManager)
                                                                .execute(
                                                                        tx -> {
                                                                            workerPid.set(
                                                                                    jdbc
                                                                                            .queryForObject(
                                                                                                    "SELECT pg_backend_pid()",
                                                                                                    Integer
                                                                                                            .class));
                                                                            started.countDown();
                                                                            return checkout();
                                                                        })));
                                awaitStarted(started);
                                awaitDatabaseLock(workerPid.get());
                            });
            assertThatThrownBy(() -> reservation.get().get(10, TimeUnit.SECONDS))
                    .hasCauseInstanceOf(ApiException.class)
                    .satisfies(
                            error ->
                                    assertThat(((ApiException) error.getCause()).code())
                                            .isEqualTo("INSUFFICIENT_STOCK"));
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders WHERE member_id=?",
                                Integer.class,
                                member))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reserved_points FROM members WHERE id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM product_option WHERE id=?",
                                Long.class,
                                OPTION))
                .isNull();
    }

    @ParameterizedTest
    @CsvSource({"OPTION,SOLD_OUT", "PRODUCT,HIDDEN"})
    void unlimitedReservationHoldsStatusStableUntilItsCheckoutCommits(
            String target, String changedStatus) throws Exception {
        var reserved = new CountDownLatch(1);
        var allowCommit = new CountDownLatch(1);
        var writerStarted = new CountDownLatch(1);
        var writerPid = new AtomicInteger();
        try (var pool = Executors.newFixedThreadPool(2)) {
            var checkout =
                    pool.submit(
                            () ->
                                    new TransactionTemplate(transactionManager)
                                            .execute(
                                                    status -> {
                                                        var result = checkout();
                                                        reserved.countDown();
                                                        awaitStarted(allowCommit);
                                                        return result;
                                                    }));
            try {
                awaitStarted(reserved);
                var update =
                        pool.submit(
                                () ->
                                        new TransactionTemplate(transactionManager)
                                                .executeWithoutResult(
                                                        status -> {
                                                            writerPid.set(
                                                                    jdbc.queryForObject(
                                                                            "SELECT pg_backend_pid()",
                                                                            Integer.class));
                                                            writerStarted.countDown();
                                                            updateStatus(target, changedStatus);
                                                        }));
                awaitStarted(writerStarted);
                awaitDatabaseLock(writerPid.get());
                assertThat(update.isDone()).isFalse();
                allowCommit.countDown();
                assertThat(checkout.get(10, TimeUnit.SECONDS)).isNotNull();
                update.get(10, TimeUnit.SECONDS);
            } finally {
                allowCommit.countDown();
            }
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM orders WHERE member_id=? AND status='PAYMENT_PENDING'",
                                Integer.class,
                                member))
                .isEqualTo(1);
    }

    @Test
    void unlimitedToFiniteChangeRejectsConcurrentReservationsWithoutLockUpgrades()
            throws Exception {
        var started = new CountDownLatch(2);
        var pids = List.of(new AtomicInteger(), new AtomicInteger());
        var results = new ArrayList<Future<?>>();
        try (var pool = Executors.newFixedThreadPool(2)) {
            new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                            status -> {
                                jdbc.update(
                                        "UPDATE product_option SET available_quantity=2 WHERE id=?",
                                        OPTION);
                                for (AtomicInteger pid : pids) {
                                    results.add(
                                            pool.submit(
                                                    () ->
                                                            new TransactionTemplate(
                                                                            transactionManager)
                                                                    .executeWithoutResult(
                                                                            tx -> {
                                                                                pid.set(
                                                                                        jdbc
                                                                                                .queryForObject(
                                                                                                        "SELECT pg_backend_pid()",
                                                                                                        Integer
                                                                                                                .class));
                                                                                started.countDown();
                                                                                stock.reserve(
                                                                                        List.of(
                                                                                                new CatalogStockApi
                                                                                                        .Reservation(
                                                                                                        OPTION,
                                                                                                        1)));
                                                                            })));
                                }
                                awaitStarted(started);
                                pids.forEach(pid -> awaitDatabaseLock(pid.get()));
                            });
            for (Future<?> result : results)
                assertThatThrownBy(() -> result.get(10, TimeUnit.SECONDS))
                        .hasCauseInstanceOf(ApiException.class);
        }
        assertThat(
                        jdbc.queryForObject(
                                "SELECT available_quantity FROM product_option WHERE id=?",
                                Long.class,
                                OPTION))
                .isEqualTo(2);
    }

    private CreateResponse checkout() {
        return orders.create(
                member,
                UUID.randomUUID().toString(),
                new CreateRequest(
                        List.of(new ItemRequest(OPTION, BigDecimal.ONE)),
                        null,
                        BigDecimal.ZERO,
                        address));
    }

    private void updateStatus(String target, String status) {
        if (target.equals("OPTION"))
            jdbc.update("UPDATE product_option SET status=? WHERE id=?", status, OPTION);
        else jdbc.update("UPDATE product SET status=? WHERE id=?", status, PRODUCT);
    }

    private void awaitDatabaseLock(int backendPid) {
        await().atMost(Duration.ofSeconds(5))
                .until(
                        () ->
                                Boolean.TRUE.equals(
                                        jdbc.queryForObject(
                                                "SELECT wait_event_type='Lock' FROM pg_stat_activity WHERE pid=?",
                                                Boolean.class,
                                                backendPid)));
    }

    private static void awaitStarted(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException(
                        "Concurrent transaction did not reach its checkpoint");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }
}
