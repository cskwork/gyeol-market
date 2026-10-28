package io.github.cskwork.lunepick.shared;

import java.util.List;
import org.springframework.http.HttpMethod;

/** Modules declare their routes; platform filters only apply these policies. */
public interface WebPolicyContributor {
    default List<PublicEndpoint> publicEndpoints() {
        return List.of();
    }

    default List<RateLimitRule> rateLimitRules() {
        return List.of();
    }

    default List<String> etagPaths() {
        return List.of();
    }

    record PublicEndpoint(HttpMethod method, String pattern, boolean ignoreBearer) {}

    record RateLimitRule(
            HttpMethod method,
            String pattern,
            String scope,
            long capacity,
            RateLimitKey key,
            int maxBodyBytes) {
        public RateLimitRule {
            if (capacity < 1
                    || maxBodyBytes < 0
                    || maxBodyBytes > 1_048_576
                    || (key == RateLimitKey.IP_EMAIL && maxBodyBytes == 0))
                throw new IllegalArgumentException("Invalid request budget");
        }
    }

    enum RateLimitKey {
        IP,
        MEMBER,
        IP_EMAIL
    }
}
