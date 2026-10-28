package io.github.cskwork.lunepick.shared.internal.web;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import io.github.cskwork.lunepick.shared.WebPolicyContributor;
import io.github.cskwork.lunepick.shared.internal.application.RateLimitService;
import jakarta.servlet.FilterChain;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.json.JsonMapper;

class RateLimitFilterTest {
    @Test
    void databaseFailureFailsClosedWithClientEnvelopeAndNoStorageDetails() throws Exception {
        var limits = mock(RateLimitService.class);
        when(limits.retryAfter(anyString(), anyString(), anyLong()))
                .thenThrow(new IllegalStateException("private-storage-detail"));
        var mapper = JsonMapper.builder().build();
        var filter =
                new RateLimitFilter(
                        limits,
                        List.of(
                                new WebPolicyContributor() {
                                    public List<RateLimitRule> rateLimitRules() {
                                        return List.of(
                                                new RateLimitRule(
                                                        HttpMethod.POST,
                                                        "/api/auth/login",
                                                        "login-ip",
                                                        30,
                                                        RateLimitKey.IP,
                                                        16384));
                                    }
                                }),
                        mapper);
        var request = new MockHttpServletRequest("POST", "/api/auth/login");
        request.setAttribute(RequestIdFilter.ATTRIBUTE, "rate-storage-failure");
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getHeader("Retry-After")).isEqualTo("1");
        assertThat(mapper.readTree(response.getContentAsString()).at("/error/code").asText())
                .isEqualTo("RATE_LIMIT_UNAVAILABLE");
        assertThat(response.getContentAsString()).doesNotContain("private-storage-detail");
        verifyNoInteractions(chain);
    }

    @Test
    void bodyLimitStaysEnforcedWhenBudgetStorageIsDisabled() throws Exception {
        var mapper = JsonMapper.builder().build();
        var contributor =
                new WebPolicyContributor() {
                    public List<RateLimitRule> rateLimitRules() {
                        return List.of(
                                new RateLimitRule(
                                        HttpMethod.POST,
                                        "/callback",
                                        "callback-ip",
                                        120,
                                        RateLimitKey.IP,
                                        16384));
                    }
                };
        var filter = new RateLimitFilter(null, List.of(contributor), mapper);
        var request = new MockHttpServletRequest("POST", "/callback");
        request.setContent(new byte[16385]);
        var response = new MockHttpServletResponse();
        var chain = mock(FilterChain.class);
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413);
        verifyNoInteractions(chain);
    }
}
