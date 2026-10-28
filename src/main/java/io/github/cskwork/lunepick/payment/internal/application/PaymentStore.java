package io.github.cskwork.lunepick.payment.internal.application;

import io.github.cskwork.lunepick.order.OrderApi;
import java.math.BigDecimal;
import java.time.Instant;

public interface PaymentStore {
    boolean reserve(
            OrderApi.PaymentTarget target, String paymentKey, BigDecimal amount, Instant now);

    Confirmation confirmation(String paymentKey);

    void complete(OrderApi.PaymentResult result, Instant now);

    boolean reserveWebhook(PaymentService.WebhookEvent event, String payloadHash, Instant now);

    String webhookHash(String eventId);

    int deleteProcessedWebhooksBefore(Instant cutoff);

    void webhookOutcome(String eventId, String status, String reason);

    record Confirmation(
            long orderId,
            String orderNo,
            long memberId,
            String paymentKey,
            BigDecimal amount,
            String status) {}
}
