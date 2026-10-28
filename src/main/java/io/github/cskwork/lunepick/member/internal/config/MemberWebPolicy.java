package io.github.cskwork.lunepick.member.internal.config;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;

@Component
public class MemberWebPolicy implements WebPolicyContributor {
    private final long loginIp;
    private final long loginEmail;
    private final long signupIp;

    public MemberWebPolicy(
            @Value("${lunepick.rate-limit.login-ip:30}") long loginIp,
            @Value("${lunepick.rate-limit.login-email:10}") long loginEmail,
            @Value("${lunepick.rate-limit.signup-ip:10}") long signupIp) {
        this.loginIp = loginIp;
        this.loginEmail = loginEmail;
        this.signupIp = signupIp;
    }

    public List<PublicEndpoint> publicEndpoints() {
        return List.of(
                new PublicEndpoint(HttpMethod.POST, "/api/auth/login", true),
                new PublicEndpoint(HttpMethod.POST, "/api/auth/signup", true),
                new PublicEndpoint(HttpMethod.POST, "/api/auth/refresh", true));
    }

    public List<RateLimitRule> rateLimitRules() {
        return List.of(
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/auth/login",
                        "login-ip",
                        loginIp,
                        RateLimitKey.IP,
                        16384),
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/auth/login",
                        "login-ip-email",
                        loginEmail,
                        RateLimitKey.IP_EMAIL,
                        16384),
                new RateLimitRule(
                        HttpMethod.POST,
                        "/api/auth/signup",
                        "signup-ip",
                        signupIp,
                        RateLimitKey.IP,
                        16384));
    }
}
