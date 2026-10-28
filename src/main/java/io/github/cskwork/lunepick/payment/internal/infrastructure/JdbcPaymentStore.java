package io.github.cskwork.lunepick.payment.internal.infrastructure;

import io.github.cskwork.lunepick.order.OrderApi;
import io.github.cskwork.lunepick.payment.internal.application.PaymentService.WebhookEvent;
import io.github.cskwork.lunepick.payment.internal.application.PaymentStore;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class JdbcPaymentStore implements PaymentStore {
    private final JdbcClient jdbc;

    public JdbcPaymentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean reserve(
            OrderApi.PaymentTarget target, String key, BigDecimal amount, Instant now) {
        // Both payment_key and order_id are unique; either index can win a concurrent insert race.
        return jdbc.sql(
                                """
                                INSERT INTO payment_confirmations(payment_key,order_id,order_no,member_id,amount,status,created_at)
                                VALUES (?,?,?,?,?,'PENDING',?) ON CONFLICT DO NOTHING
                                """)
                        .params(
                                key,
                                target.orderId(),
                                target.orderNo(),
                                target.memberId(),
                                amount,
                                Timestamp.from(now))
                        .update()
                == 1;
    }

    @Override
    public Confirmation confirmation(String key) {
        return jdbc.sql("SELECT * FROM payment_confirmations WHERE payment_key = ?")
                .params(key)
                .query(
                        (rs, row) ->
                                new Confirmation(
                                        rs.getLong("order_id"),
                                        rs.getString("order_no"),
                                        rs.getLong("member_id"),
                                        rs.getString("payment_key"),
                                        rs.getBigDecimal("amount"),
                                        rs.getString("status")))
                .single();
    }

    @Override
    public void complete(OrderApi.PaymentResult result, Instant now) {
        jdbc.sql(
                        "UPDATE payment_confirmations SET status = ?, confirmed_at = ? WHERE"
                                + " payment_key = ?")
                .params(result.status(), Timestamp.from(now), result.paymentKey())
                .update();
    }

    @Override
    public boolean reserveWebhook(WebhookEvent event, String hash, Instant now) {
        return jdbc.sql(
                                "INSERT INTO"
                                        + " payment_webhook_events(event_id,payload_hash,processed_at,status,order_no,payment_key,amount,event_status)"
                                        + " VALUES (?,?,?,'RECEIVED',?,?,?,?) ON CONFLICT (event_id) DO"
                                        + " NOTHING")
                        .params(
                                event.eventId(),
                                hash,
                                Timestamp.from(now),
                                event.orderNo(),
                                event.paymentKey(),
                                event.amount(),
                                event.status())
                        .update()
                == 1;
    }

    @Override
    public String webhookHash(String id) {
        return jdbc.sql("SELECT payload_hash FROM payment_webhook_events WHERE event_id = ?")
                .params(id)
                .query(String.class)
                .single();
    }

    @Override
    public int deleteProcessedWebhooksBefore(Instant cutoff) {
        return jdbc.sql(
                        "DELETE FROM payment_webhook_events WHERE status='PROCESSED' AND"
                                + " processed_at<?")
                .params(Timestamp.from(cutoff))
                .update();
    }

    @Override
    public void webhookOutcome(String eventId, String status, String reason) {
        jdbc.sql("UPDATE payment_webhook_events SET status=?,review_reason=? WHERE event_id=?")
                .params(status, reason, eventId)
                .update();
    }
}
