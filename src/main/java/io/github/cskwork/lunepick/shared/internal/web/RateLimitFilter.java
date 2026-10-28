package io.github.cskwork.lunepick.shared.internal.web;

import io.github.cskwork.lunepick.shared.ApiErrorBody;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import io.github.cskwork.lunepick.shared.internal.application.RateLimitService;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

public class RateLimitFilter extends OncePerRequestFilter {
    private final RateLimitService limits;
    private final List<WebPolicyContributor.RateLimitRule> rules;
    private final ObjectMapper mapper;

    public RateLimitFilter(
            RateLimitService limits, List<WebPolicyContributor> contributors, ObjectMapper mapper) {
        this.limits = limits;
        this.rules = contributors.stream().flatMap(c -> c.rateLimitRules().stream()).toList();
        this.mapper = mapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var matches =
                rules.stream()
                        .filter(
                                rule ->
                                        PathPatternRequestMatcher.withDefaults()
                                                .matcher(rule.method(), rule.pattern())
                                                .matches(request))
                        .toList();
        HttpServletRequest current = request;
        byte[] bytes = null;
        int maxBody =
                matches.stream()
                        .mapToInt(WebPolicyContributor.RateLimitRule::maxBodyBytes)
                        .filter(limit -> limit > 0)
                        .min()
                        .orElse(0);
        // Charge socket IP before reading/parsing any supplied body.
        for (var rule : matches) {
            if (rule.key() == WebPolicyContributor.RateLimitKey.IP
                    && limited(
                            request,
                            response,
                            rule.scope(),
                            request.getRemoteAddr(),
                            rule.capacity())) return;
        }
        if (maxBody > 0) {
            bytes = request.getInputStream().readNBytes(maxBody + 1);
            if (bytes.length > maxBody) {
                failure(request, response, 413, "PAYLOAD_TOO_LARGE", "요청 본문이 너무 큽니다.");
                return;
            }
            current = new CachedBody(request, bytes);
        }
        for (var rule : matches) {
            String subject = null;
            if (rule.key() == WebPolicyContributor.RateLimitKey.IP_EMAIL) {
                try {
                    var json = mapper.readTree(bytes);
                    if (json != null && json.has("email")) {
                        String email = json.get("email").asText("").trim().toLowerCase(Locale.ROOT);
                        if (!email.isEmpty()) subject = request.getRemoteAddr() + "\n" + email;
                    }
                } catch (RuntimeException malformed) {
                    // MVC returns its normal malformed-body envelope.
                }
            } else if (rule.key() == WebPolicyContributor.RateLimitKey.MEMBER) {
                var auth = SecurityContextHolder.getContext().getAuthentication();
                if (auth instanceof JwtAuthenticationToken jwt)
                    subject = jwt.getToken().getSubject();
            }
            if (subject != null
                    && limited(request, response, rule.scope(), subject, rule.capacity())) return;
        }
        chain.doFilter(current, response);
    }

    private boolean limited(
            HttpServletRequest request,
            HttpServletResponse response,
            String scope,
            String subject,
            long capacity)
            throws IOException {
        // Request body limits remain enforced when budget storage is disabled in tests/local.
        if (limits == null) return false;
        long retry;
        try {
            retry = limits.retryAfter(scope, subject, capacity);
        } catch (RuntimeException unavailable) {
            LoggerFactory.getLogger(RateLimitFilter.class)
                    .error(
                            "Rate limit storage unavailable: type={}",
                            unavailable.getClass().getSimpleName());
            response.setHeader("Retry-After", "1");
            failure(
                    request,
                    response,
                    503,
                    "RATE_LIMIT_UNAVAILABLE",
                    "요청을 처리하지 못했습니다. 잠시 후 다시 시도해주세요.");
            return true;
        }
        if (retry == 0) return false;
        response.setHeader("Retry-After", Long.toString(retry));
        failure(request, response, 429, "RATE_LIMITED", "요청이 너무 많습니다. 잠시 후 다시 시도해주세요.");
        return true;
    }

    private void failure(
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter()
                .write(
                        mapper.writeValueAsString(
                                ApiResponse.failure(
                                        ApiErrorBody.of(
                                                code,
                                                message,
                                                request.getRequestURI(),
                                                (String)
                                                        request.getAttribute(
                                                                RequestIdFilter.ATTRIBUTE)))));
    }

    private static class CachedBody extends HttpServletRequestWrapper {
        private final byte[] bytes;

        CachedBody(HttpServletRequest request, byte[] bytes) {
            super(request);
            this.bytes = bytes;
        }

        @Override
        public ServletInputStream getInputStream() {
            var input = new ByteArrayInputStream(bytes);
            return new ServletInputStream() {
                public int read() {
                    return input.read();
                }

                public boolean isFinished() {
                    return input.available() == 0;
                }

                public boolean isReady() {
                    return true;
                }

                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous request body");
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(
                    new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
