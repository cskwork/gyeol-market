package io.github.cskwork.lunepick.payment.internal.config;

import io.github.cskwork.lunepick.payment.internal.application.PaymentStore;
import java.time.Clock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PaymentMaintenance {
    private final PaymentStore store;
    private final PaymentProperties properties;
    private final Clock clock;

    public PaymentMaintenance(PaymentStore store, PaymentProperties properties, Clock clock) {
        this.store = store;
        this.properties = properties;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${lunepick.payment.webhook-cleanup-delay:PT1H}")
    @Transactional
    public void cleanup() {
        store.deleteProcessedWebhooksBefore(clock.instant().minus(properties.webhookRetention()));
    }
}
