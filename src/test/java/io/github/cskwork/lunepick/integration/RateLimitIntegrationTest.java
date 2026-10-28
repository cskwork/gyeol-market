package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import io.github.cskwork.lunepick.shared.internal.application.RateLimitService;
import io.github.cskwork.lunepick.shared.internal.config.RateLimitProperties;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "lunepick.rate-limit.enabled=true",
            "lunepick.rate-limit.login-ip=3",
            "lunepick.rate-limit.login-email=2",
            "lunepick.rate-limit.signup-ip=2",
            "lunepick.rate-limit.order-member=2",
            "lunepick.rate-limit.payment-member=2"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RateLimitIntegrationTest extends EmbeddedDatabase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired DataSource dataSource;
    @Autowired RateLimitService limits;
    @Autowired RateLimitProperties properties;

    @BeforeEach
    void resetBuckets() {
        jdbc.update("DELETE FROM rate_limit_buckets");
    }

    MvcResult login(String email, String ip) throws Exception {
        return mvc.perform(
                        post("/api/auth/login")
                                .with(
                                        request -> {
                                            request.setRemoteAddr(ip);
                                            return request;
                                        })
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        mapper.writeValueAsString(
                                                Map.of("email", email, "password", "wrong1234"))))
                .andReturn();
    }

    void limited(MvcResult result) throws Exception {
        new ContractAssertions(mapper).check("error-rate-limited", result);
    }

    @Test
    void loginIpAndIpEmailBudgetsDoNotLetAttackersLockOutOtherAddresses() throws Exception {
        assertThat(login("fixture-a@example.test", "192.0.2.1").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(login("fixture-b@example.test", "192.0.2.1").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(login("fixture-c@example.test", "192.0.2.1").getResponse().getStatus())
                .isEqualTo(401);
        limited(login("fixture-d@example.test", "192.0.2.1"));
        assertThat(login(" normalized@example.test ", "192.0.2.2").getResponse().getStatus())
                .isEqualTo(401);
        assertThat(login("NORMALIZED@example.test", "192.0.2.2").getResponse().getStatus())
                .isEqualTo(401);
        limited(login("normalized@example.test", "192.0.2.2"));
        assertThat(login("normalized@example.test", "192.0.2.4").getResponse().getStatus())
                .isEqualTo(401);
    }

    @Test
    void signupOrderAndPaymentBudgetsReturnTheSame429Envelope() throws Exception {
        for (int i = 0; i < 2; i++)
            assertThat(
                            mvc.perform(
                                            post("/api/auth/signup")
                                                    .contentType(MediaType.APPLICATION_JSON)
                                                    .content("{}"))
                                    .andReturn()
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(400);
        limited(
                mvc.perform(
                                post("/api/auth/signup")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("{}"))
                        .andReturn());
        for (String route : List.of("/api/orders", "/api/payments/confirm")) {
            for (int i = 0; i < 2; i++)
                assertThat(
                                mvc.perform(
                                                post(route)
                                                        .with(jwt().jwt(j -> j.subject("1")))
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .content("{}"))
                                        .andReturn()
                                        .getResponse()
                                        .getStatus())
                        .isEqualTo(400);
            limited(
                    mvc.perform(
                                    post(route)
                                            .with(jwt().jwt(j -> j.subject("1")))
                                            .contentType(MediaType.APPLICATION_JSON)
                                            .content("{}"))
                            .andReturn());
        }
    }

    @Test
    void differentProxyManagersCompeteForOneDatabaseBudgetAndExpiredRowsAreCleaned()
            throws Exception {
        var second = new RateLimitService(dataSource, properties, jdbc);
        assertThat(limits.retryAfter("instances", "same-budget", 1)).isZero();
        assertThat(second.retryAfter("instances", "same-budget", 1)).isPositive();
        CountDownLatch start = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<Long>> calls = new ArrayList<>();
            for (int i = 0; i < 24; i++) {
                var manager = i % 2 == 0 ? limits : second;
                calls.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return manager.retryAfter("parallel", "shared-budget", 10);
                                }));
            }
            start.countDown();
            int allowed = 0;
            for (var call : calls) if (call.get(15, TimeUnit.SECONDS) == 0) allowed++;
            assertThat(allowed).isEqualTo(10);
        }
        jdbc.update("UPDATE rate_limit_buckets SET expires_at=0");
        limits.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM rate_limit_buckets", Integer.class))
                .isZero();
    }

    @Test
    void loginBodyIsBoundedBeforeJsonAndHashing() throws Exception {
        var result =
                mvc.perform(
                                post("/api/auth/login")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content("x".repeat(16385)))
                        .andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(413);
        assertThat(
                        mapper.readTree(result.getResponse().getContentAsString())
                                .at("/error/code")
                                .asText())
                .isEqualTo("PAYLOAD_TOO_LARGE");
    }
}
