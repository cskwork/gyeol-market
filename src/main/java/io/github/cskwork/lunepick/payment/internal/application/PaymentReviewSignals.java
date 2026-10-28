package io.github.cskwork.lunepick.payment.internal.application;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Record operational signals only when the corresponding review record commits. */
@Component
public class PaymentReviewSignals {
    private static final Logger log = LoggerFactory.getLogger(PaymentReviewSignals.class);
    private final Counter reviews;

    public PaymentReviewSignals(MeterRegistry meters) {
        reviews = meters.counter("lunepick.payment.webhook.needs_review");
    }

    public void committedReview(long orderId, String eventId, String reason) {
        Runnable signal =
                () -> {
                    log.warn(
                            "Payment needs review: orderId={} eventId={} reason={}",
                            orderId,
                            eventId,
                            reason);
                    reviews.increment();
                };
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            signal.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        signal.run();
                    }
                });
    }
}
