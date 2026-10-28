package io.github.cskwork.lunepick.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
            "management.server.port=0",
            "management.server.address=127.0.0.1",
            "management.endpoints.web.exposure.include=health,info,metrics,prometheus"
        })
@ActiveProfiles("test")
class ManagementPortIntegrationTest extends EmbeddedDatabase {
    @LocalServerPort int applicationPort;

    @Value("${local.management.port}")
    int managementPort;

    @Autowired ObjectMapper mapper;

    @Test
    void operatorsScrapeOnlyTheSeparateListenerWhileShopperHealthStaysPublic() throws Exception {
        assertThat(managementPort).isPositive().isNotEqualTo(applicationPort);
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()) {
            var health = get(client, applicationPort, "/actuator/health", null);
            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(health.body()).path("status").asText()).isEqualTo("UP");

            var login =
                    client.send(
                            HttpRequest.newBuilder(uri(applicationPort, "/api/auth/login"))
                                    .header("Content-Type", "application/json")
                                    .POST(
                                            HttpRequest.BodyPublishers.ofString(
                                                    "{\"email\":\"demo@example.com\",\"password\":\"demo1234\"}"))
                                    .timeout(Duration.ofSeconds(10))
                                    .build(),
                            HttpResponse.BodyHandlers.ofString());
            assertThat(login.statusCode()).isEqualTo(200);
            String access = mapper.readTree(login.body()).at("/data/accessToken").asText();
            assertThat(get(client, applicationPort, "/actuator/metrics", access).statusCode())
                    .isEqualTo(403);
            assertThat(get(client, applicationPort, "/actuator/prometheus", access).statusCode())
                    .isEqualTo(403);

            var metrics = get(client, managementPort, "/actuator/metrics", null);
            assertThat(metrics.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(metrics.body()).path("names").isArray()).isTrue();
            var prometheus = get(client, managementPort, "/actuator/prometheus", null);
            assertThat(prometheus.statusCode()).isEqualTo(200);
            assertThat(prometheus.body()).contains("# HELP");
            assertThat(get(client, managementPort, "/api/products", null).statusCode())
                    .isEqualTo(403);
        }
    }

    private HttpResponse<String> get(HttpClient client, int port, String path, String token)
            throws Exception {
        var request = HttpRequest.newBuilder(uri(port, path)).timeout(Duration.ofSeconds(10));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(int port, String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
