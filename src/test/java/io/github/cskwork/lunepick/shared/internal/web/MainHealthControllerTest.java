package io.github.cskwork.lunepick.shared.internal.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.boot.health.actuate.endpoint.IndicatedHealthDescriptor;
import org.springframework.boot.health.contributor.Status;

class MainHealthControllerTest {
    @Test
    void publicHealthReportsOnlyStatusAndUsesServiceUnavailableForDown() {
        var endpoint = mock(HealthEndpoint.class);
        var health = mock(IndicatedHealthDescriptor.class);
        when(endpoint.health()).thenReturn(health);
        var controller = new MainHealthController(endpoint);
        when(health.getStatus()).thenReturn(Status.UP);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(200);
        assertThat(controller.health().getBody()).isEqualTo(Map.of("status", "UP"));
        when(health.getStatus()).thenReturn(Status.DOWN);
        assertThat(controller.health().getStatusCode().value()).isEqualTo(503);
        assertThat(controller.health().getBody()).isEqualTo(Map.of("status", "DOWN"));
    }
}
