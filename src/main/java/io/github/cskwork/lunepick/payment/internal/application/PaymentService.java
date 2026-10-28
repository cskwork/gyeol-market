package io.github.cskwork.lunepick.payment.internal.application;

import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.shared.ApiException;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
@Transactional
public class PaymentService {
    private final OrderApi orders;
    private final PaymentStore store;
    private final Clock clock;
    private final PaymentGateway gateway;
    private final PaymentCompensation compensation;
    private final PaymentReviewSignals reviewSignals;

    public PaymentService(
            OrderApi orders,
            PaymentStore store,
            Clock clock,
            PaymentGateway gateway,
            PaymentCompensation compensation,
            PaymentReviewSignals reviewSignals) {
        this.orders = orders;
        this.store = store;
        this.clock = clock;
        this.gateway = gateway;
        this.compensation = compensation;
        this.reviewSignals = reviewSignals;
    }

    public Confirmation confirm(
            long memberId, String orderNo, String paymentKey, BigDecimal amount) {
        var target = orders.paymentTarget(orderNo);
        if (target.memberId() != memberId)
            throw new ApiException(404, "ORDER_NOT_FOUND", "주문을 찾을 수 없습니다.");
        validate(target, paymentKey, amount);
        // PostgreSQL waits for a competing insert; only its committed result can be replayed.
        if (!store.reserve(target, paymentKey, amount, clock.instant())) {
            var existing = store.confirmation(paymentKey);
            if (existing.memberId() != memberId
                    || !existing.orderNo().equals(orderNo)
                    || existing.amount().compareTo(amount) != 0) throw mismatch();
            return new Confirmation(
                    existing.orderId(),
                    existing.orderNo(),
                    existing.status(),
                    existing.paymentKey());
        }
        if (!"PAYMENT_PENDING".equals(target.status()))
            throw new ApiException(409, "INVALID_ORDER_STATE", "주문 상태를 확인해주세요.");
        var result = orders.confirm(memberId, orderNo, paymentKey, amount);
        store.complete(result, clock.instant());
        var confirmation =
                new Confirmation(
                        result.orderId(), result.orderNo(), result.status(), result.paymentKey());
        var captured = new AtomicBoolean();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            if (!captured.get()) return;
                            if (status == STATUS_ROLLED_BACK)
                                compensation.reconcileCapture(
                                        target,
                                        paymentKey,
                                        amount,
                                        PaymentCompensation.CompletionOutcome.ROLLED_BACK);
                            else if (status == STATUS_UNKNOWN)
                                compensation.reconcileCapture(
                                        target,
                                        paymentKey,
                                        amount,
                                        PaymentCompensation.CompletionOutcome.UNKNOWN);
                        }
                    });
        }
        // Every local mutation is prepared before capture; rollback compensation also covers commit
        // failures.
        try {
            gateway.confirm(paymentKey, orderNo, amount);
        } catch (PaymentGateway.Failure failure) {
            if (failure.outcome() == PaymentGateway.Outcome.UNKNOWN)
                compensation.reconcileCapture(
                        target,
                        paymentKey,
                        amount,
                        PaymentCompensation.CompletionOutcome.GATEWAY_UNKNOWN);
            throw failure;
        } catch (RuntimeException transportFailure) {
            // Only an explicit provider decline proves that no capture took place.
            compensation.reconcileCapture(
                    target,
                    paymentKey,
                    amount,
                    PaymentCompensation.CompletionOutcome.GATEWAY_UNKNOWN);
            throw PaymentGateway.Failure.unknown(transportFailure);
        }
        captured.set(true);
        return confirmation;
    }

    public WebhookAck webhook(WebhookEvent event, String payloadHash) {
        if (!store.reserveWebhook(event, payloadHash, clock.instant())) {
            if (!payloadHash.equals(store.webhookHash(event.eventId())))
                throw new ApiException(409, "WEBHOOK_EVENT_CONFLICT", "이미 처리된 웹훅과 내용이 다릅니다.");
            return new WebhookAck(event.eventId(), false);
        }
        var target = orders.paymentTarget(event.orderNo());
        if (!Set.of("CONFIRMED", "FAILED", "CANCELLED").contains(event.status()))
            throw new ApiException(400, "VALIDATION_FAILED", "입력값을 확인해주세요.");
        String mismatch = mismatchReason(target, event.paymentKey(), event.amount());
        if (mismatch != null) {
            needsReview(target, event.eventId(), mismatch);
            return new WebhookAck(event.eventId(), true);
        }
        String desired = "CONFIRMED".equals(event.status()) ? "PAID" : event.status();
        if (!"PAYMENT_PENDING".equals(target.status()) && !desired.equals(target.status())) {
            needsReview(target, event.eventId(), target.status() + " -> " + desired);
            return new WebhookAck(event.eventId(), true);
        }
        switch (event.status()) {
            case "CONFIRMED" -> {
                store.webhookOutcome(event.eventId(), "PROCESSED", null);
                confirm(target.memberId(), event.orderNo(), event.paymentKey(), event.amount());
            }
            case "FAILED", "CANCELLED" -> {
                if ("PAYMENT_PENDING".equals(target.status()))
                    gateway.cancel(event.paymentKey(), event.orderNo(), event.amount());
                orders.fail(event.orderNo(), event.paymentKey(), event.amount(), event.status());
                store.webhookOutcome(event.eventId(), "PROCESSED", null);
            }
            default -> throw new ApiException(400, "VALIDATION_FAILED", "입력값을 확인해주세요.");
        }
        return new WebhookAck(event.eventId(), true);
    }

    private void needsReview(OrderApi.PaymentTarget target, String eventId, String reason) {
        store.webhookOutcome(eventId, "NEEDS_REVIEW", reason);
        orders.holdForReconciliation(target.orderId(), reason);
        reviewSignals.committedReview(target.orderId(), eventId, reason);
    }

    private String mismatchReason(OrderApi.PaymentTarget target, String key, BigDecimal amount) {
        if (!target.paymentKey().equals(key)) return "PAYMENT_KEY_MISMATCH";
        if (amount == null || target.amount().compareTo(amount) != 0)
            return "PAYMENT_AMOUNT_MISMATCH";
        return null;
    }

    private void validate(OrderApi.PaymentTarget target, String key, BigDecimal amount) {
        if (mismatchReason(target, key, amount) != null) throw mismatch();
    }

    private ApiException mismatch() {
        return new ApiException(422, "PAYMENT_AMOUNT_MISMATCH", "결제 금액이 주문 금액과 일치하지 않습니다.");
    }

    public record Confirmation(long orderId, String orderNo, String status, String paymentKey) {}

    public record WebhookEvent(
            String eventId, String orderNo, String paymentKey, BigDecimal amount, String status) {}

    public record WebhookAck(String eventId, boolean processed) {}
}
