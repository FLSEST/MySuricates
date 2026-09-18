package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.config.RuntimeClasspathGuard;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kern-Auskunft unter /api/v1/system: Health, Plattform-Info, Auth-Status und
 * Client-Fehler-Meldungen. Die übrigen /system-Endpunkte sind nach Zuständigkeit
 * aufgeteilt: {@link SystemAiProviderController}, {@link SystemMaintenanceController},
 * {@link SystemSettingsController}, {@link SystemAccessController}.
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final DockerClient dockerClient;
    private final DataSource dataSource;
    private final SystemIdentityService systemIdentity;
    private final TailscaleService tailscaleService;
    private final RateLimitConfig rateLimitConfig;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${automation.environment:Dev}")
    private String environment;

    @GetMapping("/auth-status")
    public Map<String, Object> authStatus() {
        return Map.of("securityEnabled", securityEnabled);
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        boolean dockerAvailable;
        try {
            dockerClient.pingCmd().exec();
            dockerAvailable = true;
        } catch (Exception | Error e) {
            dockerAvailable = false;
        }

        boolean dbAvailable;
        long dbResponseTimeMs;
        long start = System.nanoTime();
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            dbAvailable = true;
        } catch (Exception e) {
            dbAvailable = false;
        }
        dbResponseTimeMs = (System.nanoTime() - start) / 1_000_000;

        String status;
        if (!dbAvailable) {
            status = "DOWN";
        } else if (!dockerAvailable) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }

        Map<String, Object> dbComponent = new LinkedHashMap<>();
        dbComponent.put("status", dbAvailable ? "UP" : "DOWN");
        dbComponent.put("responseTimeMs", dbResponseTimeMs);

        Map<String, Object> dockerComponent = new LinkedHashMap<>();
        dockerComponent.put("status", dockerAvailable ? "UP" : "DOWN");

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", dbComponent);
        components.put("docker", dockerComponent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("timestamp", Instant.now().toString());
        result.put("docker", dockerAvailable ? "CONNECTED" : "UNAVAILABLE");
        result.put("components", components);
        return result;
    }

    record ClientErrorReport(String message, String stack, String source, String url, String timestamp) {}

    @PostMapping("/client-errors")
    public ResponseEntity<Void> reportClientError(@RequestBody ClientErrorReport report) {
        log.warn("[CLIENT] {} | source={} url={} | {}", report.message(), report.source(), report.url(),
                report.stack() != null ? report.stack().lines().findFirst().orElse("") : "no stack");
        return ResponseEntity.accepted().build();
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", systemIdentity.getName());
        result.put("version", systemIdentity.getVersion());
        result.put("buildType", systemIdentity.getBuildType());
        result.put("buildTimestamp", systemIdentity.getBuildTimestamp());
        result.put("java", System.getProperty("java.version"));
        result.put("runtimeMode", RuntimeClasspathGuard.mode(System.getProperty("java.class.path")));
        result.put("tailscale", tailscaleService.isConfigEnabled() ? "CONFIGURED" : "DISABLED");
        result.put("environment", environment);
        result.put("rateLimit", rateLimitInfo());
        return result;
    }

    /**
     * Rate-Limit-Kennzahlen dieser Instanz für den Browser-Client: sein 429-Backoff soll die
     * Zahlen nicht raten, sondern von der Instanz beziehen, gegen die er läuft (37b98c97).
     * Der Endpunkt ist bewusst öffentlich — hier steht nichts, was ein 429 nicht ohnehin verrät.
     */
    private Map<String, Object> rateLimitInfo() {
        Map<String, Object> rateLimit = new LinkedHashMap<>();
        rateLimit.put("enabled", rateLimitConfig.isEnabled());
        rateLimit.put("requestsPerMinute", rateLimitConfig.getRequestsPerMinute());
        rateLimit.put("globalBackoffSeconds", rateLimitConfig.getClientGlobalBackoffSeconds());
        rateLimit.put("defaultRetryAfterSeconds", rateLimitConfig.getClientDefaultRetryAfterSeconds());
        return rateLimit;
    }
}
