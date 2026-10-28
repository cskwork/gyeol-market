package io.github.cskwork.lunepick.payment.internal.config;

import jakarta.validation.constraints.AssertTrue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("lunepick.payment")
public record PaymentProperties(
        String webhookSecret, Integer webhookRequestsPerMinute, Duration webhookRetention) {
    public PaymentProperties(String webhookSecret) {
        this(webhookSecret, 120, Duration.ofDays(30));
    }

    @ConstructorBinding
    public PaymentProperties {
        webhookRetention = webhookRetention == null ? Duration.ofDays(30) : webhookRetention;
        if (webhookRetention.isNegative() || webhookRetention.isZero())
            throw new IllegalArgumentException("Webhook retention must be positive");
        webhookRequestsPerMinute =
                webhookRequestsPerMinute == null ? 120 : webhookRequestsPerMinute;
        if (webhookRequestsPerMinute < 1)
            throw new IllegalArgumentException("Webhook rate must be positive");
    }

    @AssertTrue(message = "webhook secret must contain at least 32 UTF-8 bytes")
    public boolean isWebhookSecretValid() {
        return webhookSecret == null || webhookSecret.getBytes(StandardCharsets.UTF_8).length >= 32;
    }
}
