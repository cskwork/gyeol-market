package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import io.github.cskwork.lunepick.member.internal.application.LoginFailureGuard;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        properties = {
            "lunepick.rate-limit.enabled=true",
            "lunepick.rate-limit.login-ip=20",
            "lunepick.rate-limit.login-email=2",
            "lunepick.auth.login-failures.threshold=3",
            "lunepick.auth.login-failures.minimum-distinct-ips=3",
            "lunepick.auth.login-failures.window=1m",
            "lunepick.auth.login-failures.cooldown=30s"
        })
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginFailuresIntegrationTest extends EmbeddedDatabase {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired JdbcTemplate jdbc;
    @Autowired LoginFailureGuard guard;
    @Autowired TestClock clock;
    private static final Instant NOW = Instant.parse("2026-10-28T00:00:00Z");

    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        @Bean
        @Primary
        TestClock loginFailureClock() {
            return new TestClock();
        }
    }

    static class TestClock extends Clock {
        volatile Instant now = NOW;

        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        public Clock withZone(ZoneId zone) {
            return this;
        }

        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void resetCounters() {
        jdbc.update("DELETE FROM login_failure_budgets");
        jdbc.update("DELETE FROM rate_limit_buckets");
        clock.now = NOW;
    }

    @Test
    void successfulAndInvalidRequestsNeverConsumeTheFailureBudget() throws Exception {
        for (int i = 1; i <= 5; i++)
            assertThat(
                            login("demo@example.com", "demo1234", "192.0.2." + i)
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(200);
        assertThat(
                        mvc.perform(
                                        post("/api/auth/login")
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content("{\"email\":\"demo@example.com\"}"))
                                .andReturn()
                                .getResponse()
                                .getStatus())
                .isEqualTo(400);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_failure_budgets", Integer.class))
                .isZero();
    }

    @Test
    void distributedGuessingCreatesShortSharedCooldownAndItExpires() throws Exception {
        for (int i = 1; i <= 3; i++)
            assertThat(
                            login("demo@example.com", "incorrect-password", "192.0.2." + i)
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(401);
        var blocked = login("demo@example.com", "demo1234", "192.0.2.4");
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(blocked.getResponse().getHeader("Retry-After")).isEqualTo("30");
        assertThat(
                        mapper.readTree(blocked.getResponse().getContentAsString())
                                .at("/error/code")
                                .asText())
                .isEqualTo("RATE_LIMITED");
        assertThat(jdbc.queryForObject("SELECT failures FROM login_failure_budgets", Integer.class))
                .isEqualTo(3);
        assertThat(
                        jdbc.queryForObject(
                                "SELECT email_hash || origin_hashes FROM login_failure_budgets",
                                String.class))
                .doesNotContain("demo@example.com", "192.0.2", "incorrect-password");

        clock.now = NOW.plusSeconds(31);
        assertThat(login("demo@example.com", "demo1234", "192.0.2.5").getResponse().getStatus())
                .isEqualTo(200);
        assertThat(jdbc.queryForObject("SELECT failures FROM login_failure_budgets", Integer.class))
                .isEqualTo(3);
        clock.now = NOW.plusSeconds(61);
        assertThat(
                        login("demo@example.com", "incorrect-password", "192.0.2.6")
                                .getResponse()
                                .getStatus())
                .isEqualTo(401);
        assertThat(jdbc.queryForObject("SELECT failures FROM login_failure_budgets", Integer.class))
                .isEqualTo(1);
        clock.now = NOW.plusSeconds(180);
        guard.cleanup();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM login_failure_budgets", Integer.class))
                .isZero();
    }

    @Test
    void oneAttackerIpCannotLockTheVictimOutFromAnotherAddress() throws Exception {
        for (int i = 0; i < 12; i++)
            assertThat(
                            login("demo@example.com", "incorrect-password", "192.0.2.1")
                                    .getResponse()
                                    .getStatus())
                    .isEqualTo(i < 2 ? 401 : 429);
        assertThat(jdbc.queryForObject("SELECT failures FROM login_failure_budgets", Integer.class))
                .isEqualTo(2);
        assertThat(login("demo@example.com", "demo1234", "192.0.2.2").getResponse().getStatus())
                .isEqualTo(200);
        // Even a misconfigured high IP budget cannot turn one origin into a shared account block.
        for (int i = 0; i < 20; i++) guard.recordFailure("demo@example.com", "192.0.2.1");
        assertThat(guard.retryAfter("demo@example.com")).isZero();
    }

    @Test
    void concurrentFailureCountersAcrossInstancesDoNotLoseAttempts() throws Exception {
        try (var pool = Executors.newFixedThreadPool(3)) {
            var start = new CountDownLatch(1);
            var tasks =
                    IntStream.rangeClosed(1, 3)
                            .mapToObj(
                                    index ->
                                            pool.submit(
                                                    () -> {
                                                        start.await();
                                                        guard.recordFailure(
                                                                "unknown@example.test",
                                                                "192.0.2." + index);
                                                        return true;
                                                    }))
                            .toList();
            start.countDown();
            for (var task : tasks) task.get();
        }
        assertThat(jdbc.queryForObject("SELECT failures FROM login_failure_budgets", Integer.class))
                .isEqualTo(3);
        assertThat(guard.retryAfter(" UNKNOWN@Example.test ")).isEqualTo(30);
    }

    private MvcResult login(String email, String password, String ip) throws Exception {
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
                                                Map.of("email", email, "password", password))))
                .andReturn();
    }
}
