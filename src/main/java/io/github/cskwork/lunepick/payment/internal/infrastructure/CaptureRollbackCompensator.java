package io.github.cskwork.lunepick.payment.internal.infrastructure;

import io.github.cskwork.lunepick.order.OrderApi.PaymentTarget;
import io.github.cskwork.lunepick.payment.internal.application.PaymentCompensation;
import io.github.cskwork.lunepick.payment.internal.application.PaymentGateway;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class CaptureRollbackCompensator implements PaymentCompensation {
    private static final Logger log = LoggerFactory.getLogger(CaptureRollbackCompensator.class);
    private final PaymentGateway gateway;
    private final PaymentReconciliationRecorder recorder;

    public CaptureRollbackCompensator(
            PaymentGateway gateway, PaymentReconciliationRecorder recorder) {
        this.gateway = gateway;
        this.recorder = recorder;
    }

    @Override
    public void reconcileCapture(
            PaymentTarget order, String paymentKey, BigDecimal amount, CompletionOutcome outcome) {
        if (outcome == CompletionOutcome.UNKNOWN) {
            persistReview(
                    order,
                    paymentKey,
                    amount,
                    "CAPTURE_SUCCEEDED_COMMIT_OUTCOME_UNKNOWN; cancellation=NOT_ATTEMPTED");
            return;
        }
        String incident =
                outcome == CompletionOutcome.GATEWAY_UNKNOWN
                        ? "GATEWAY_CONFIRM_OUTCOME_UNKNOWN"
                        : "CAPTURE_SUCCEEDED_LOCAL_ROLLBACK";
        String reason = incident + "; cancellation=SUCCEEDED";
        try {
            gateway.cancel(paymentKey, order.orderNo(), amount);
        } catch (RuntimeException failure) {
            reason = incident + "; cancellation=FAILED";
            log.warn(
                    "Payment compensation failed: orderId={} paymentKey={} reason={}",
                    order.orderId(),
                    paymentKey,
                    reason,
                    failure);
        }
        persistReview(order, paymentKey, amount, reason);
    }

    private void persistReview(
            PaymentTarget order, String paymentKey, BigDecimal amount, String reason) {
        try {
            recorder.record(order, paymentKey, amount, reason);
        } catch (RuntimeException failure) {
            // Preserve the original transaction failure; an unavailable database requires an alert.
            log.error(
                    "Cannot persist payment review: orderId={} paymentKey={} reason={}",
                    order.orderId(),
                    paymentKey,
                    reason,
                    failure);
        }
    }
}
