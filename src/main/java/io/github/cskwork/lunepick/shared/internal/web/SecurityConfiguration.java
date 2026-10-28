package io.github.cskwork.lunepick.shared.internal.web;

import io.github.cskwork.lunepick.shared.ApiErrorBody;
import io.github.cskwork.lunepick.shared.ApiResponse;
import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import tools.jackson.databind.ObjectMapper;

@EnableWebSecurity
@Configuration(proxyBeanMethods = false)
public class SecurityConfiguration {
    @Bean
    @Order(0)
    SecurityFilterChain operatorSecurityFilterChain(HttpSecurity http, Environment environment)
            throws Exception {
        return http.securityMatcher(
                        request -> {
                            Integer managementPort =
                                    environment.getProperty("local.management.port", Integer.class);
                            Integer applicationPort =
                                    environment.getProperty("local.server.port", Integer.class);
                            return managementPort != null
                                    && managementPort > 0
                                    && !managementPort.equals(applicationPort)
                                    && request.getLocalPort() == managementPort;
                        })
                .csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth ->
                                auth.requestMatchers(
                                                HttpMethod.GET,
                                                "/actuator/health",
                                                "/actuator/health/**",
                                                "/actuator/info",
                                                "/actuator/metrics",
                                                "/actuator/metrics/**",
                                                "/actuator/prometheus")
                                        .permitAll()
                                        .anyRequest()
                                        .denyAll())
                .build();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper mapper,
            List<WebPolicyContributor> contributors,
            ObjectProvider<RateLimitFilter> rateLimitFilter)
            throws Exception {
        rateLimitFilter.ifAvailable(
                filter -> http.addFilterAfter(filter, BearerTokenAuthenticationFilter.class));
        return http.csrf(csrf -> csrf.disable())
                .sessionManagement(
                        session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(
                        auth -> {
                            for (var contributor : contributors) {
                                for (var endpoint : contributor.publicEndpoints()) {
                                    auth.requestMatchers(endpoint.method(), endpoint.pattern())
                                            .permitAll();
                                }
                            }
                            auth.requestMatchers(
                                            HttpMethod.GET,
                                            "/images/**",
                                            "/app/**",
                                            "/actuator/health",
                                            "/actuator/info",
                                            "/v3/api-docs/**",
                                            "/swagger-ui/**",
                                            "/swagger-ui.html")
                                    .permitAll()
                                    .requestMatchers("/actuator/**")
                                    .denyAll()
                                    .anyRequest()
                                    .authenticated();
                        })
                .exceptionHandling(
                        errors ->
                                errors.authenticationEntryPoint(
                                                (request, response, e) ->
                                                        write(
                                                                mapper,
                                                                request,
                                                                response,
                                                                401,
                                                                "UNAUTHORIZED",
                                                                "로그인이 필요합니다."))
                                        .accessDeniedHandler(
                                                (request, response, e) ->
                                                        write(
                                                                mapper,
                                                                request,
                                                                response,
                                                                403,
                                                                "FORBIDDEN",
                                                                "접근 권한이 없습니다.")))
                .oauth2ResourceServer(
                        resource ->
                                resource.bearerTokenResolver(
                                                request -> {
                                                    boolean ignore =
                                                            contributors.stream()
                                                                    .flatMap(
                                                                            c ->
                                                                                    c
                                                                                            .publicEndpoints()
                                                                                            .stream())
                                                                    .filter(
                                                                            WebPolicyContributor
                                                                                            .PublicEndpoint
                                                                                    ::ignoreBearer)
                                                                    .anyMatch(
                                                                            e ->
                                                                                    PathPatternRequestMatcher
                                                                                            .withDefaults()
                                                                                            .matcher(
                                                                                                    e
                                                                                                            .method(),
                                                                                                    e
                                                                                                            .pattern())
                                                                                            .matches(
                                                                                                    request));
                                                    if (ignore) return null;
                                                    return new DefaultBearerTokenResolver()
                                                            .resolve(request);
                                                })
                                        .jwt(jwt -> {})
                                        .authenticationEntryPoint(
                                                (request, response, e) ->
                                                        write(
                                                                mapper,
                                                                request,
                                                                response,
                                                                401,
                                                                "UNAUTHORIZED",
                                                                "로그인이 필요합니다.")))
                .build();
    }

    private void write(
            ObjectMapper mapper,
            HttpServletRequest request,
            HttpServletResponse response,
            int status,
            String code,
            String message)
            throws IOException {
        response.setStatus(status);
        response.setHeader("Cache-Control", "no-store");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
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
}
