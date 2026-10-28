package io.github.cskwork.lunepick.order;

import java.math.BigDecimal;

/** Payment operations preserve order ownership, amount and reservation state. */
public interface OrderApi {
    PaymentResult confirm(long memberId, String orderNo, String paymentKey, BigDecimal amount);

    /** Locks the order row until the calling payment transaction completes. */
    PaymentTarget paymentTarget(String orderNo);

    PaymentResult fail(String orderNo, String paymentKey, BigDecimal amount, String state);

    /** Joins the caller's transaction without acquiring the potentially locked order row. */
    void holdForReconciliation(long orderId, String reason);

    record PaymentResult(
            long orderId, String orderNo, long memberId, String status, String paymentKey) {}

    record PaymentTarget(
            long orderId,
            String orderNo,
            long memberId,
            String paymentKey,
            BigDecimal amount,
            String status) {}
}
