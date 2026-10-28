package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.order.OrderApi.PaymentTarget;
import io.github.cskwork.lunepick.order.internal.application.OrderDtos.*;
import io.github.cskwork.lunepick.order.internal.application.OrderService;
import io.github.cskwork.lunepick.payment.internal.application.PaymentCompensation;
import io.github.cskwork.lunepick.payment.internal.application.PaymentGateway;
import io.github.cskwork.lunepick.payment.internal.application.PaymentService;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.UnexpectedRollbackException;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

@SpringBootTest
@ActiveProfiles("test")
@Import(PaymentGatewayIntegrationTest.GatewayConfiguration.class)
class PaymentGatewayIntegrationTest extends EmbeddedDatabase {
    @Autowired OrderService orders;
    @Autowired PaymentService payments;
    @Autowired JdbcTemplate jdbc;
    @Autowired ControllableGateway gateway;
    @Autowired TransactionTemplate transactions;
    @Autowired PaymentCompensation compensation;
    long member;
    long option;
    CreateResponse order;

    @TestConfiguration(proxyBeanMethods = false)
    static class GatewayConfiguration {
        @Bean
        @Primary
        ControllableGateway controllableGateway(JdbcTemplate jdbc) {
            return new ControllableGateway(jdbc);
        }
    }

    static class ControllableGateway implements PaymentGateway {
        final JdbcTemplate jdbc;
        boolean reject;
        boolean failCommit;
        boolean rejectCancellation;
        boolean timeout;
        boolean transportError;
        int confirmations;
        int cancellations;

        ControllableGateway(JdbcTemplate jdbc) {
            this.jdbc = jdbc;
        }

        public void confirm(String key, String no, BigDecimal amount) {
            assertThat(
                            jdbc.queryForObject(
                                    "select status from orders where order_no=?", String.class, no))
                    .isEqualTo("PAID");
            if (reject) throw PaymentGateway.Failure.declined();
            assertThat(
                            jdbc.queryForObject(
                                    "select status from payment_confirmations where payment_key=?",
                                    String.class,
                                    key))
                    .isEqualTo("PAID");
            confirmations++;
            if (timeout)
                throw PaymentGateway.Failure.unknown(
                        new SocketTimeoutException(
                                "synthetic provider timeout after request sent"));
            if (transportError) throw new IllegalStateException("synthetic transport disconnected");
            if (failCommit)
                TransactionSynchronizationManager.registerSynchronization(
                        new TransactionSynchronization() {
                            @Override
                            public void beforeCommit(boolean readOnly) {
                                throw new IllegalStateException(
                                        "synthetic commit failure after capture");
                            }
                        });
        }

        public void cancel(String key, String no, BigDecimal amount) {
            cancellations++;
            if (reject || rejectCancellation) throw PaymentGateway.Failure.declined();
        }
    }

    @BeforeEach
    void fixture() {
        gateway.reject = false;
        gateway.failCommit = false;
        gateway.rejectCancellation = false;
        gateway.timeout = false;
        gateway.transportError = false;
        gateway.confirmations = 0;
        gateway.cancellations = 0;
        member =
                jdbc.queryForObject(
                        "insert into members(email,password_hash,name,created_at)"
                                + " values(?,'unused','gateway',now()) returning id",
                        Long.class,
                        UUID.randomUUID() + "@example.test");
        long address =
                jdbc.queryForObject(
                        "insert into"
                                + " addresses(member_id,recipient_name,phone,zipcode,address_main,is_default)"
                                + " values(?,'name','01000000000','00000','address',true) returning id",
                        Long.class,
                        member);
        option =
                jdbc.queryForObject(
                        "insert into"
                                + " product_option(id,product_id,name,price,status,available_quantity,sort_order)"
                                + " values(nextval('order_payment_key_seq'),1,'gateway',0,'ON_SALE',2,99)"
                                + " returning id",
                        Long.class);
        order =
                orders.create(
                        member,
                        "gateway",
                        new CreateRequest(
                                List.of(new ItemRequest(option, BigDecimal.ONE)),
                                null,
                                new BigDecimal("100"),
                                address));
    }

    @Test
    void providerRejectionRollsBackPaymentAttemptAndLeavesReservationsHeld() {
        gateway.reject = true;
        assertThatThrownBy(
                        () ->
                                payments.confirm(
                                        member,
                                        order.orderNo(),
                                        Long.toString(order.paymentKey()),
                                        order.amount()))
                .isInstanceOfSatisfying(
                        PaymentGateway.Failure.class,
                        e -> {
                            assertThat(e.status()).isEqualTo(502);
                            assertThat(e.outcome()).isEqualTo(PaymentGateway.Outcome.DECLINED);
                        });
        assertPendingReservations();
        assertThat(reviewCount()).isZero();
        assertThat(holdCount()).isZero();
        assertThat(gateway.cancellations).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_confirmations where order_no=?",
                                Long.class,
                                order.orderNo()))
                .isZero();
        gateway.reject = false;
        assertThat(
                        payments.confirm(
                                        member,
                                        order.orderNo(),
                                        Long.toString(order.paymentKey()),
                                        order.amount())
                                .status())
                .isEqualTo("PAID");
    }

    @Test
    void providerTimeoutRecordsIndependentReviewAttemptsCancelAndPreventsAutomaticExpiry() {
        gateway.timeout = true;
        assertThatThrownBy(() -> confirm())
                .isInstanceOfSatisfying(
                        PaymentGateway.Failure.class,
                        failure -> {
                            assertThat(failure.outcome()).isEqualTo(PaymentGateway.Outcome.UNKNOWN);
                            assertThat(failure.status()).isEqualTo(503);
                            assertThat(failure.getCause())
                                    .isInstanceOf(SocketTimeoutException.class);
                        });
        assertUnknownGatewayReview("SUCCEEDED");

        jdbc.update(
                "UPDATE orders SET created_at=created_at - interval '1 day' WHERE order_no=?",
                order.orderNo());
        orders.expirePending(Duration.ofMinutes(30), 100);
        assertPendingReservations();
        assertThat(gateway.cancellations).isEqualTo(1);
        assertThat(reviewCount()).isEqualTo(1);
    }

    @Test
    void timeoutReviewIsCommittedBeforeSurroundingTransactionRollsBack() {
        gateway.timeout = true;
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            assertThatThrownBy(() -> confirm())
                                                    .isInstanceOf(PaymentGateway.Failure.class);
                                            // Independent case and expiry hold are durable while
                                            // the caller is rollback-only.
                                            assertThat(reviewCount()).isEqualTo(1);
                                            assertThat(holdCount()).isEqualTo(1);
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT status FROM orders WHERE order_no=?",
                                                                    String.class,
                                                                    order.orderNo()))
                                                    .isEqualTo("PAID");
                                        }))
                .isInstanceOf(UnexpectedRollbackException.class);
        assertUnknownGatewayReview("SUCCEEDED");
    }

    @Test
    void timeoutAndCancellationFailureStillRecordReviewWithoutMaskingTimeout() {
        gateway.timeout = true;
        gateway.rejectCancellation = true;
        assertThatThrownBy(() -> confirm())
                .isInstanceOfSatisfying(
                        PaymentGateway.Failure.class,
                        failure ->
                                assertThat(failure.outcome())
                                        .isEqualTo(PaymentGateway.Outcome.UNKNOWN));
        assertUnknownGatewayReview("FAILED");
    }

    @Test
    void unclassifiedTransportFailureIsConservativelyUnknown() {
        gateway.transportError = true;
        assertThatThrownBy(() -> confirm())
                .isInstanceOfSatisfying(
                        PaymentGateway.Failure.class,
                        failure -> {
                            assertThat(failure.outcome()).isEqualTo(PaymentGateway.Outcome.UNKNOWN);
                            assertThat(failure.getCause())
                                    .isInstanceOf(IllegalStateException.class);
                        });
        assertUnknownGatewayReview("SUCCEEDED");
    }

    @Test
    void successfulGatewayApprovalSettlesOnceAndReplayDoesNotCallProvider() {
        var first =
                payments.confirm(
                        member, order.orderNo(), Long.toString(order.paymentKey()), order.amount());
        assertThat(
                        payments.confirm(
                                member,
                                order.orderNo(),
                                Long.toString(order.paymentKey()),
                                order.amount()))
                .isEqualTo(first);
        assertThat(gateway.confirmations).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("4900");
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isZero();
    }

    @Test
    void rejectedCancellationCannotReleaseReservationsOrConsumeWebhookEvent() {
        gateway.reject = true;
        assertThatThrownBy(
                        () ->
                                payments.webhook(
                                        new PaymentService.WebhookEvent(
                                                "rejected-cancel",
                                                order.orderNo(),
                                                Long.toString(order.paymentKey()),
                                                order.amount(),
                                                "CANCELLED"),
                                        "hash"))
                .isInstanceOf(ApiException.class);
        assertPendingReservations();
        assertThat(
                        jdbc.queryForObject(
                                "select count(*) from payment_webhook_events where"
                                        + " event_id='rejected-cancel'",
                                Long.class))
                .isZero();
        gateway.reject = false;
        assertThat(
                        payments.webhook(
                                        new PaymentService.WebhookEvent(
                                                "rejected-cancel",
                                                order.orderNo(),
                                                Long.toString(order.paymentKey()),
                                                order.amount(),
                                                "CANCELLED"),
                                        "hash")
                                .processed())
                .isTrue();
        assertThat(
                        jdbc.queryForObject(
                                "select available_quantity from product_option where id=?",
                                Long.class,
                                option))
                .isEqualTo(2);
    }

    @Test
    void localFailureAfterCaptureCancelsAndPersistsReviewOutsideRolledBackTransaction() {
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            payments.confirm(
                                                    member,
                                                    order.orderNo(),
                                                    Long.toString(order.paymentKey()),
                                                    order.amount());
                                            throw new IllegalStateException(
                                                    "synthetic local failure after capture");
                                        }))
                .hasMessageContaining("synthetic local failure");
        assertCompensatedRollback("SUCCEEDED");
    }

    @Test
    void beforeCommitFailureAfterCaptureCancelsAndPersistsReviewOutsideRolledBackTransaction() {
        gateway.failCommit = true;
        assertThatThrownBy(
                        () ->
                                payments.confirm(
                                        member,
                                        order.orderNo(),
                                        Long.toString(order.paymentKey()),
                                        order.amount()))
                .hasMessageContaining("synthetic commit failure");
        assertCompensatedRollback("SUCCEEDED");
    }

    @Test
    void deferredConstraintFailsAtDatabaseCommitAfterCaptureAndCompensates() {
        jdbc.execute(
                "CREATE TABLE IF NOT EXISTS payment_commit_probe "
                        + "(id bigint, CONSTRAINT payment_commit_probe_unique UNIQUE(id) DEFERRABLE INITIALLY DEFERRED)");
        var completion = new AtomicInteger(-1);
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            TransactionSynchronizationManager
                                                    .registerSynchronization(
                                                            new TransactionSynchronization() {
                                                                @Override
                                                                public void afterCompletion(
                                                                        int outcome) {
                                                                    completion.set(outcome);
                                                                }
                                                            });
                                            payments.confirm(
                                                    member,
                                                    order.orderNo(),
                                                    Long.toString(order.paymentKey()),
                                                    order.amount());
                                            jdbc.update(
                                                    "INSERT INTO payment_commit_probe(id) VALUES (?),(?)",
                                                    order.paymentKey(),
                                                    order.paymentKey());
                                            // Both conflicting rows are accepted by PostgreSQL
                                            // before COMMIT.
                                            assertThat(
                                                            jdbc.queryForObject(
                                                                    "SELECT count(*) FROM payment_commit_probe WHERE id=?",
                                                                    Integer.class,
                                                                    order.paymentKey()))
                                                    .isEqualTo(2);
                                        }))
                .hasRootCauseInstanceOf(SQLException.class);
        assertThat(completion.get()).isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
        assertCompensatedRollback("SUCCEEDED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_commit_probe WHERE id=?",
                                Integer.class,
                                order.paymentKey()))
                .isZero();
    }

    @Test
    void unknownCommitOutcomeIsRetainedForReviewWithoutCancellingPossibleCommittedPayment() {
        compensation.reconcileCapture(
                new PaymentTarget(
                        orders.detail(member, order.orderNo()).id(),
                        order.orderNo(),
                        member,
                        Long.toString(order.paymentKey()),
                        order.amount(),
                        "PAYMENT_PENDING"),
                Long.toString(order.paymentKey()),
                order.amount(),
                PaymentCompensation.CompletionOutcome.UNKNOWN);
        assertThat(gateway.cancellations).isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM payment_reconciliation_cases WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("CAPTURE_SUCCEEDED_COMMIT_OUTCOME_UNKNOWN; cancellation=NOT_ATTEMPTED");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_reconciliation_cases WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void cancellationFailureStillPersistsReviewAndDoesNotMaskOriginalFailure() {
        gateway.rejectCancellation = true;
        assertThatThrownBy(
                        () ->
                                transactions.executeWithoutResult(
                                        status -> {
                                            payments.confirm(
                                                    member,
                                                    order.orderNo(),
                                                    Long.toString(order.paymentKey()),
                                                    order.amount());
                                            throw new IllegalStateException(
                                                    "synthetic local failure after capture");
                                        }))
                .hasMessageContaining("synthetic local failure");
        assertCompensatedRollback("FAILED");
    }

    private void assertCompensatedRollback(String cancellation) {
        assertThat(gateway.confirmations).isEqualTo(1);
        assertThat(gateway.cancellations).isEqualTo(1);
        assertPendingReservations();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_confirmations WHERE order_no=?",
                                Integer.class,
                                order.orderNo()))
                .isZero();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT status FROM payment_reconciliation_cases WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("NEEDS_REVIEW");
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM payment_reconciliation_cases WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("CAPTURE_SUCCEEDED_LOCAL_ROLLBACK; cancellation=" + cancellation);
    }

    private PaymentService.Confirmation confirm() {
        return payments.confirm(
                member, order.orderNo(), Long.toString(order.paymentKey()), order.amount());
    }

    private int reviewCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM payment_reconciliation_cases WHERE order_no=?",
                Integer.class,
                order.orderNo());
    }

    private int holdCount() {
        return jdbc.queryForObject(
                "SELECT count(*) FROM order_reconciliation_holds WHERE order_id=?",
                Integer.class,
                orders.detail(member, order.orderNo()).id());
    }

    private void assertUnknownGatewayReview(String cancellation) {
        assertThat(gateway.confirmations).isEqualTo(1);
        assertThat(gateway.cancellations).isEqualTo(1);
        assertPendingReservations();
        assertThat(
                        jdbc.queryForObject(
                                "SELECT count(*) FROM payment_confirmations WHERE order_no=?",
                                Integer.class,
                                order.orderNo()))
                .isZero();
        assertThat(reviewCount()).isEqualTo(1);
        assertThat(holdCount()).isEqualTo(1);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT reason FROM payment_reconciliation_cases WHERE order_no=?",
                                String.class,
                                order.orderNo()))
                .isEqualTo("GATEWAY_CONFIRM_OUTCOME_UNKNOWN; cancellation=" + cancellation);
    }

    private void assertPendingReservations() {
        assertThat(orders.detail(member, order.orderNo()).status()).isEqualTo("PAYMENT_PENDING");
        assertThat(
                        jdbc.queryForObject(
                                "select point_balance from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("5000");
        assertThat(
                        jdbc.queryForObject(
                                "select reserved_points from members where id=?",
                                BigDecimal.class,
                                member))
                .isEqualByComparingTo("100");
        assertThat(
                        jdbc.queryForObject(
                                "select available_quantity from product_option where id=?",
                                Long.class,
                                option))
                .isEqualTo(1);
    }
}
