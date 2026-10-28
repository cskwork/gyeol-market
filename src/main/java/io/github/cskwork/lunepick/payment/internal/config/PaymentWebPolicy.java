package io.github.cskwork.lunepick.payment.internal.config;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class PaymentWebPolicy implements WebPolicyContributor {
    private final PaymentProperties properties;
    private final long confirmCapacity;

    public PaymentWebPolicy(
            PaymentProperties properties,
            @Value("${lunepick.rate-limit.payment-member:30}") long confirmCapacity) {
        this.properties = properties;
        this.confirmCapacity = confirmCapacity;
    }

    @Override
    public List<PublicEndpoint> publicEndpoints() {
        return List.of(new PublicEndpoint(HttpMethod.POST, "/api/payments/webhook", true));
    }

    @Override
    public List<RateLimitRule> rateLimitRules() {
        return List.of(
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/payments/confirm",
                        "payment-member",
                        confirmCapacity,
                        RateLimitKey.MEMBER,
                        0),
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/payments/webhook",
                        "payment-webhook",
                        properties.webhookRequestsPerMinute(),
                        RateLimitKey.IP,
                        16384));
    }
}
