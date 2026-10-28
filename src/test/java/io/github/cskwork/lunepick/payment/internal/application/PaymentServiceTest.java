package io.github.cskwork.lunepick.payment.internal.application;

import static org.assertj.core.api.Assertions.*;

import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.shared.ApiException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class PaymentServiceTest {
    private final MemoryOrders orders = new MemoryOrders();
    private final MemoryStore store = new MemoryStore();
    private final Gateway gateway = new Gateway();
    private final PaymentService service =
            new PaymentService(
                    orders,
                    store,
                    Clock.fixed(Instant.parse("2026-10-28T00:00:00Z"), ZoneOffset.UTC),
                    gateway,
                    (order, key, amount, outcome) -> {
                        throw new AssertionError(
                                "Transaction recovery is covered by database tests");
                    },
                    new PaymentReviewSignals(new SimpleMeterRegistry()));

    @Test
    void successfulConfirmationIsReplayedWithoutChargingOrSettlingTwice() {
        var first = service.confirm(7, "O-1", "key", BigDecimal.TEN);
        assertThat(service.confirm(7, "O-1", "key", BigDecimal.TEN)).isEqualTo(first);
        assertThat(first.status()).isEqualTo("PAID");
        assertThat(orders.settlements).isEqualTo(1);
        assertThat(gateway.charges).isEqualTo(1);
        assertThat(store.payment.status()).isEqualTo("PAID");
    }

    @Test
    void foreignOrderAndMismatchedPaymentDoNotSettle() {
        assertThatThrownBy(() -> service.confirm(8, "O-1", "wrong", BigDecimal.ONE))
                .isInstanceOfSatisfying(
                        ApiException.class, e -> assertThat(e.status()).isEqualTo(404));
        for (Object[] input :
                new Object[][] {{"wrong", BigDecimal.TEN}, {"key", BigDecimal.ONE}, {"key", null}})
            assertThatThrownBy(
                            () ->
                                    service.confirm(
                                            7, "O-1", (String) input[0], (BigDecimal) input[1]))
                    .isInstanceOfSatisfying(
                            ApiException.class,
                            e -> assertThat(e.code()).isEqualTo("PAYMENT_AMOUNT_MISMATCH"));
        assertThat(orders.state).isEqualTo("PAYMENT_PENDING");
        assertThat(store.payment).isNull();
        assertThat(gateway.charges).isZero();
    }

    @Test
    void providerRejectionPropagatesAfterPreparingLocalSettlement() {
        gateway.reject = true;
        assertThatThrownBy(() -> service.confirm(7, "O-1", "key", BigDecimal.TEN))
                .isInstanceOfSatisfying(
                        PaymentGateway.Failure.class,
                        failure ->
                                assertThat(failure.outcome())
                                        .isEqualTo(PaymentGateway.Outcome.DECLINED));
        // This pure in-memory fake has no transaction manager. The integration test verifies
        // rollback.
        assertThat(orders.state).isEqualTo("PAID");
        assertThat(orders.settlements).isEqualTo(1);
        assertThat(store.payment.status()).isEqualTo("PAID");
    }

    @Test
    void terminalOrderCannotBeCharged() {
        orders.state = "CANCELLED";
        assertThatThrownBy(() -> service.confirm(7, "O-1", "key", BigDecimal.TEN))
                .isInstanceOf(ApiException.class);
        assertThat(gateway.charges).isZero();
    }

    @Test
    void webhookConfirmationUsesSameResultAndDuplicateDoesNothing() {
        assertThat(service.webhook(event("CONFIRMED"), "hash").processed()).isTrue();
        assertThat(service.webhook(event("CONFIRMED"), "hash").processed()).isFalse();
        assertThat(service.confirm(7, "O-1", "key", BigDecimal.TEN).status()).isEqualTo("PAID");
        assertThat(gateway.charges).isEqualTo(1);
    }

    @Test
    void failureAndCancellationReleaseOnlyOnce() {
        for (String state : List.of("FAILED", "CANCELLED")) {
            var o = new MemoryOrders();
            var s = new MemoryStore();
            var g = new Gateway();
            var service =
                    new PaymentService(
                            o,
                            s,
                            Clock.systemUTC(),
                            g,
                            (order, key, amount, outcome) -> {},
                            new PaymentReviewSignals(new SimpleMeterRegistry()));
            assertThat(service.webhook(event(state), "hash").processed()).isTrue();
            assertThat(service.webhook(event(state), "hash").processed()).isFalse();
            assertThat(o.state).isEqualTo(state);
            assertThat(o.releases).isEqualTo(1);
            assertThat(g.cancellations).isEqualTo(1);
        }
    }

    @Test
    void outOfOrderTerminalEventsAreAcknowledgedAndRetainedForReview() {
        orders.state = "PAID";
        assertThat(service.webhook(event("CANCELLED"), "hash").processed()).isTrue();
        assertThat(store.outcome).isEqualTo("NEEDS_REVIEW");
        assertThat(store.reason).isEqualTo("PAID -> CANCELLED");
        assertThat(orders.state).isEqualTo("PAID");
        assertThat(gateway.cancellations).isZero();
        assertThat(service.webhook(event("CANCELLED"), "hash").processed()).isFalse();
    }

    @Test
    void alteredDuplicateAndUnknownStatusAreExplicitFailures() {
        service.webhook(event("FAILED"), "hash");
        assertThatThrownBy(() -> service.webhook(event("FAILED"), "other"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("WEBHOOK_EVENT_CONFLICT"));
        var fresh =
                new PaymentService(
                        new MemoryOrders(),
                        new MemoryStore(),
                        Clock.systemUTC(),
                        gateway,
                        (order, key, amount, outcome) -> {},
                        new PaymentReviewSignals(new SimpleMeterRegistry()));
        assertThatThrownBy(() -> fresh.webhook(event("UNKNOWN"), "hash"))
                .isInstanceOfSatisfying(
                        ApiException.class,
                        e -> assertThat(e.code()).isEqualTo("VALIDATION_FAILED"));
    }

    @Test
    void webhookMismatchIsAcknowledgedWithoutCapturingOrSettling() {
        var mismatch =
                new PaymentService.WebhookEvent("evt", "O-1", "wrong", BigDecimal.TEN, "CONFIRMED");
        assertThat(service.webhook(mismatch, "hash").processed()).isTrue();
        assertThat(store.outcome).isEqualTo("NEEDS_REVIEW");
        assertThat(store.reason).isEqualTo("PAYMENT_KEY_MISMATCH");
        assertThat(orders.state).isEqualTo("PAYMENT_PENDING");
        assertThat(gateway.charges).isZero();
    }

    @Test
    void ambiguousCommitDispatchesReviewInsteadOfRollbackCancellation() {
        var outcomes = new ArrayList<PaymentCompensation.CompletionOutcome>();
        var service =
                new PaymentService(
                        orders,
                        store,
                        Clock.systemUTC(),
                        gateway,
                        (order, key, amount, outcome) -> outcomes.add(outcome),
                        new PaymentReviewSignals(new SimpleMeterRegistry()));
        TransactionSynchronizationManager.initSynchronization();
        List<TransactionSynchronization> synchronizations;
        try {
            service.confirm(7, "O-1", "key", BigDecimal.TEN);
            synchronizations = TransactionSynchronizationManager.getSynchronizations();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
        synchronizations.forEach(
                sync -> sync.afterCompletion(TransactionSynchronization.STATUS_UNKNOWN));
        assertThat(outcomes).containsExactly(PaymentCompensation.CompletionOutcome.UNKNOWN);
        assertThat(gateway.charges).isEqualTo(1);
        assertThat(gateway.cancellations).isZero();
    }

    private PaymentService.WebhookEvent event(String state) {
        return new PaymentService.WebhookEvent("evt", "O-1", "key", BigDecimal.TEN, state);
    }

    static class MemoryOrders implements OrderApi {
        String state = "PAYMENT_PENDING";
        int settlements;
        int releases;

        public void holdForReconciliation(long orderId, String reason) {}

        public PaymentTarget paymentTarget(String no) {
            return new PaymentTarget(1, no, 7, "key", BigDecimal.TEN, state);
        }

        public PaymentResult confirm(long member, String no, String key, BigDecimal amount) {
            state = "PAID";
            settlements++;
            return new PaymentResult(1, no, member, state, key);
        }

        public PaymentResult fail(String no, String key, BigDecimal amount, String failure) {
            if (!state.equals(failure)) {
                releases++;
                state = failure;
            }
            return new PaymentResult(1, no, 7, state, key);
        }
    }

    static class Gateway implements PaymentGateway {
        boolean reject;
        int charges;
        int cancellations;

        public void confirm(String key, String no, BigDecimal amount) {
            if (reject) throw PaymentGateway.Failure.declined();
            charges++;
        }

        public void cancel(String key, String no, BigDecimal amount) {
            if (reject) throw PaymentGateway.Failure.declined();
            cancellations++;
        }
    }

    static class MemoryStore implements PaymentStore {
        Confirmation payment;
        String hash;
        String outcome;
        String reason;

        public boolean reserve(
                OrderApi.PaymentTarget target, String key, BigDecimal amount, Instant now) {
            if (payment != null) return false;
            payment = new Confirmation(1, "O-1", 7, key, amount, "PENDING");
            return true;
        }

        public Confirmation confirmation(String key) {
            return payment;
        }

        public void complete(OrderApi.PaymentResult result, Instant now) {
            payment =
                    new Confirmation(
                            1, "O-1", 7, result.paymentKey(), BigDecimal.TEN, result.status());
        }

        public boolean reserveWebhook(
                PaymentService.WebhookEvent event, String value, Instant now) {
            if (hash != null) return false;
            hash = value;
            return true;
        }

        public String webhookHash(String id) {
            return hash;
        }

        public int deleteProcessedWebhooksBefore(Instant cutoff) {
            return 0;
        }

        public void webhookOutcome(String id, String state, String cause) {
            outcome = state;
            reason = cause;
        }
    }
}
