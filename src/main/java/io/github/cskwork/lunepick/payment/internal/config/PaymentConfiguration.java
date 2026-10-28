package io.github.cskwork.lunepick.payment.internal.config;

import io.github.cskwork.lunepick.payment.internal.application.PaymentGateway;
import io.github.cskwork.lunepick.payment.internal.domain.WebhookSignature;
import io.github.cskwork.lunepick.payment.internal.infrastructure.FakePaymentGateway;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentProperties.class)
class PaymentConfiguration {
    @Bean
    @Profile("(local | test) & !prod & !production")
    @ConditionalOnMissingBean(PaymentGateway.class)
    PaymentGateway fakePaymentGateway() {
        return new FakePaymentGateway();
    }

    static final String LOCAL_SECRET = "lunepick-local-webhook-secret-32-bytes-minimum";

    @Bean
    WebhookSignature webhookSignature(
            PaymentProperties properties, Environment environment, Clock clock) {
        String secret = properties.webhookSecret();
        if (secret == null) secret = environment.getProperty("LUNEPICK_PAYMENT_WEBHOOK_SECRET");
        boolean development =
                !environment.acceptsProfiles(Profiles.of("prod", "production"))
                        && environment.acceptsProfiles(Profiles.of("local", "test"));
        if (secret == null && development) secret = LOCAL_SECRET;
        if (!development && (secret == null || secret.equals(LOCAL_SECRET)))
            throw new IllegalStateException(
                    "Configure lunepick.payment.webhook-secret outside local/test profiles");
        return new WebhookSignature(secret, clock);
    }
}
