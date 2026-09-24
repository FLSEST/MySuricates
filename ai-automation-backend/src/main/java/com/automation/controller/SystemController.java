package com.automation.controller;

import com.automation.config.RuntimeClasspathGuard;
import com.automation.security.RateLimitConfig;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kern-Auskunft unter /api/v1/system: Health, Plattform-Info, Auth-Status, Heap-Belegung und
 * Client-Fehler-Meldungen. Die übrigen /system-Endpunkte sind nach Zuständigkeit
 * aufgeteilt: {@link SystemAiProviderController}, {@link SystemMaintenanceController},
 * {@link SystemSettingsController}, {@link SystemAccessController}.
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final DataSource dataSource;
    private final SystemIdentityService systemIdentity;
    private final TailscaleService tailscaleService;
    private final RateLimitConfig rateLimitConfig;
    private final DockerExpectation dockerExpectation;

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
        DockerExpectation.State docker = dockerExpectation.probe();

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

        // Kein Docker eingerichtet ist ein Normalzustand, kein gestoerter Betrieb (a0e85182);
        // DEGRADED nur, wenn Docker da sein sollte und nicht antwortet.
        String status;
        if (!dbAvailable) {
            status = "DOWN";
        } else if (docker == DockerExpectation.State.DOWN) {
            status = "DEGRADED";
        } else {
            status = "UP";
        }

        Map<String, Object> dbComponent = new LinkedHashMap<>();
        dbComponent.put("status", dbAvailable ? "UP" : "DOWN");
        dbComponent.put("responseTimeMs", dbResponseTimeMs);

        Map<String, Object> dockerComponent = new LinkedHashMap<>();
        dockerComponent.put("status", docker.name());

        Map<String, Object> components = new LinkedHashMap<>();
        components.put("database", dbComponent);
        components.put("docker", dockerComponent);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", status);
        result.put("timestamp", Instant.now().toString());
        result.put("docker", switch (docker) {
            case UP -> "CONNECTED";
            case DOWN -> "UNAVAILABLE";
            case NOT_CONFIGURED -> "NOT_CONFIGURED";
        });
        result.put("components", components);
        return result;
    }

    /**
     * Heap-Belegung als genau drei Zahlen, ohne API-Key (c2cda5ed): der Performance-Audit misst
     * damit den Speicher, ohne dass die ganze Metrik-Oberfläche unter {@code /actuator/metrics}
     * frei wird — die verrät Routen und Lastprofil und bleibt hinter dem Key. Quelle ist der
     * MemoryMXBean, nicht Micrometer: {@code jvm.memory.max} summiert über Pools, deren Maximum
     * undefiniert ({@code -1}) ist.
     */
    @GetMapping("/jvm-memory")
    public Map<String, Object> jvmMemory() {
        return jvmMemory(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
    }

    /** Ohne bekanntes Maximum ({@code -1}) bleiben Maximum und Anteil leer statt erfunden. */
    static Map<String, Object> jvmMemory(MemoryUsage heap) {
        long used = heap.getUsed();
        Long max = heap.getMax() > 0 ? heap.getMax() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jvm_memory_used_bytes", used);
        result.put("jvm_memory_max_bytes", max);
        result.put("jvm_heap_used_percent", max == null ? null : Math.round(used * 1000.0 / max) / 10.0);
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
