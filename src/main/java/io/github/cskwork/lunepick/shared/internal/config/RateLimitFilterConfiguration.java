package io.github.cskwork.lunepick.shared.internal.config;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import io.github.cskwork.lunepick.shared.internal.application.RateLimitService;
import io.github.cskwork.lunepick.shared.internal.web.RateLimitFilter;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class RateLimitFilterConfiguration {
    @Bean
    RateLimitFilter rateLimitFilter(
            ObjectProvider<RateLimitService> limits,
            List<WebPolicyContributor> contributors,
            ObjectMapper mapper) {
        return new RateLimitFilter(limits.getIfAvailable(), contributors, mapper);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> rateLimitFilterRegistration(RateLimitFilter filter) {
        var registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false); // Security chain owns ordering after bearer authentication.
        return registration;
    }
}
