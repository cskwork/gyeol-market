package io.github.cskwork.lunepick.shared.internal.web;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import java.util.List;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.filter.ShallowEtagHeaderFilter;

/** Revalidation preserves fresh data while allowing clients to avoid repeated payload transfer. */
@Configuration(proxyBeanMethods = false)
public class ResponseCachingConfiguration {
    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> responseEtagFilter(
            List<WebPolicyContributor> contributors) {
        var registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        var patterns =
                contributors.stream().flatMap(c -> c.etagPaths().stream()).toArray(String[]::new);
        if (patterns.length == 0) registration.setEnabled(false);
        else registration.addUrlPatterns(patterns);
        return registration;
    }
}
