package io.github.cskwork.lunepick.payment.internal.application;

import io.github.cskwork.lunepick.order.OrderApi.PaymentTarget;
import java.math.BigDecimal;

/** Review persistence is independent of the local transaction, including an in-flight timeout. */
public interface PaymentCompensation {
    void reconcileCapture(
            PaymentTarget order, String paymentKey, BigDecimal amount, CompletionOutcome outcome);

    enum CompletionOutcome {
        ROLLED_BACK,
        UNKNOWN,
        GATEWAY_UNKNOWN
    }
}
