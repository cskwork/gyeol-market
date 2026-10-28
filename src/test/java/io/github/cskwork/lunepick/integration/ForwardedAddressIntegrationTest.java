package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.*;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

abstract class ForwardedAddressChecks extends EmbeddedDatabase {
    @LocalServerPort int port;

    abstract boolean trustsLoopback();

    int login(HttpClient client, String forwardedIp) throws Exception {
        return client.send(
                        HttpRequest.newBuilder(
                                        URI.create("http://127.0.0.1:" + port + "/api/auth/login"))
                                .header("X-Forwarded-For", forwardedIp)
                                .header("Content-Type", "application/json")
                                .POST(
                                        HttpRequest.BodyPublishers.ofString(
                                                "{\"email\":\"proxy-budget@example.test\",\"password\":\"incorrect123\"}"))
                                .timeout(Duration.ofSeconds(15))
                                .build(),
                        HttpResponse.BodyHandlers.discarding())
                .statusCode();
    }

    @Test
    void forwardedAddressChangesBudgetOnlyWhenSocketPeerIsExplicitlyTrusted() throws Exception {
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            assertThat(login(client, "192.0.2.10")).isEqualTo(401);
            assertThat(login(client, "192.0.2.11")).isEqualTo(trustsLoopback() ? 401 : 429);
            assertThat(login(client, "192.0.2.10")).isEqualTo(429);
        }
    }
}

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "lunepick.rate-limit.enabled=true",
            "lunepick.rate-limit.login-ip=1",
            "lunepick.rate-limit.login-email=1"
        })
@ActiveProfiles("test")
class UntrustedForwardedAddressIntegrationTest extends ForwardedAddressChecks {
    boolean trustsLoopback() {
        return false;
    }
}

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "server.tomcat.remoteip.internal-proxies=127\\.0\\.0\\.1",
            "lunepick.rate-limit.enabled=true",
            "lunepick.rate-limit.login-ip=1",
            "lunepick.rate-limit.login-email=1"
        })
@ActiveProfiles("test")
class TrustedForwardedAddressIntegrationTest extends ForwardedAddressChecks {
    boolean trustsLoopback() {
        return true;
    }
}
