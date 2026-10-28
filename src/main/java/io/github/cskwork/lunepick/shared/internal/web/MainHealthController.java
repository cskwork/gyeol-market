package io.github.cskwork.lunepick.shared.internal.web;

import java.util.Map;
import java.util.Set;
import org.springframework.boot.health.actuate.endpoint.HealthEndpoint;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Keep a detail-free health check on the shopper listener when management uses another port. */
@RestController
public class MainHealthController {
    private final HealthEndpoint health;

    public MainHealthController(HealthEndpoint health) {
        this.health = health;
    }

    @GetMapping("/actuator/health")
    public ResponseEntity<Map<String, String>> health() {
        String status = health.health().getStatus().getCode();
        int code = Set.of("DOWN", "OUT_OF_SERVICE").contains(status) ? 503 : 200;
        return ResponseEntity.status(code).body(Map.of("status", status));
    }
}
