package io.github.cskwork.lunepick.order.internal.config;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class OrderWebPolicy implements WebPolicyContributor {
    private final long capacity;

    public OrderWebPolicy(@Value("${lunepick.rate-limit.order-member:20}") long capacity) {
        this.capacity = capacity;
    }

    @Override
    public List<RateLimitRule> rateLimitRules() {
        return List.of(
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/orders",
                        "order-member",
                        capacity,
                        RateLimitKey.MEMBER,
                        0));
    }
}
