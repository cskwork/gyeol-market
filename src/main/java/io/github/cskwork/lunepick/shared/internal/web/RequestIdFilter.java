package io.github.cskwork.lunepick.shared.internal.web;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.server.PathContainer;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.pattern.PathPatternParser;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RequestIdFilter extends OncePerRequestFilter {
    public static final String ATTRIBUTE = RequestIdFilter.class.getName() + ".requestId";

    private final List<String> cachePatterns;

    public RequestIdFilter(List<WebPolicyContributor> contributors) {
        cachePatterns =
                contributors.stream()
                        .flatMap(c -> c.etagPaths().stream())
                        .map(pattern -> pattern.endsWith("/*") ? pattern + "*" : pattern)
                        .toList();
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String supplied = request.getHeader("X-Request-Id");
        String id =
                supplied != null && supplied.matches("[A-Za-z0-9._-]{1,64}")
                        ? supplied
                        : UUID.randomUUID().toString();
        String path = request.getRequestURI();
        if ("GET".equals(request.getMethod())
                && cachePatterns.stream()
                        .anyMatch(
                                pattern ->
                                        PathPatternParser.defaultInstance
                                                .parse(pattern)
                                                .matches(PathContainer.parsePath(path)))) {
            response.setHeader("Cache-Control", "public, no-cache");
        }
        request.setAttribute(ATTRIBUTE, id);
        response.setHeader("X-Request-Id", id);
        MDC.put("requestId", id);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove("requestId");
        }
    }
}
