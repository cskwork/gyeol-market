package io.github.cskwork.lunepick.payment.internal.infrastructure;

import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.order.OrderApi.PaymentTarget;
import io.github.cskwork.lunepick.payment.internal.application.PaymentReviewSignals;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentReconciliationRecorder {
    private final JdbcClient jdbc;
    private final Clock clock;
    private final PaymentReviewSignals signals;
    private final OrderApi orders;

    public PaymentReconciliationRecorder(
            JdbcClient jdbc, Clock clock, PaymentReviewSignals signals, OrderApi orders) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.signals = signals;
        this.orders = orders;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(PaymentTarget order, String paymentKey, BigDecimal amount, String reason) {
        UUID eventId = UUID.randomUUID();
        jdbc.sql(
                        """
                INSERT INTO payment_reconciliation_cases
                    (id,order_id,order_no,payment_key,amount,status,reason,created_at)
                VALUES (:id,:orderId,:orderNo,:paymentKey,:amount,'NEEDS_REVIEW',:reason,:createdAt)
                """)
                .param("id", eventId)
                .param("orderId", order.orderId())
                .param("orderNo", order.orderNo())
                .param("paymentKey", paymentKey)
                .param("amount", amount)
                .param("reason", reason)
                .param("createdAt", Timestamp.from(clock.instant()))
                .update();
        // This inserts an independent hold without locking the outer transaction's order row.
        orders.holdForReconciliation(order.orderId(), reason);
        signals.committedReview(order.orderId(), eventId.toString(), reason);
    }
}
