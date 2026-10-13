package com.automation.controller;

import com.automation.security.RateLimitConfig;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.ListenAddresses;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.automation.util.RuntimeClasspathGuard;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
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
import java.util.regex.Pattern;

/**
 * Core information under /api/v1/system: health, platform info, listen addresses, auth status, heap usage and
 * client error reports. The other /system endpoints are split by concern:
 * {@link SystemAiProviderController}, {@link SystemMaintenanceController},
 * {@link SystemSettingsController}, {@link SystemAccessController}.
 */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    /**
     * C0/C1 control characters (CR, LF, TAB, ESC, NEL, …), format characters (bidi overrides and isolates,
     * zero-width marks, BOM) and the Unicode line and paragraph separators.
     */
    private static final Pattern LOG_BREAKING_CHARS = Pattern.compile("[\\p{Cc}\\p{Cf}\\p{Zl}\\p{Zp}]+");
    /** Fixed log lengths per field: one client report stays one bounded log line. */
    private static final int LOG_MESSAGE_MAX = 1_000;
    private static final int LOG_SOURCE_MAX = 64;
    private static final int LOG_URL_MAX = 500;
    private static final int LOG_STACK_MAX = 500;

    private final DataSource dataSource;
    private final SystemIdentityService systemIdentity;
    private final TailscaleService tailscaleService;
    private final RateLimitConfig rateLimitConfig;
    private final DockerExpectation dockerExpectation;
    private final ListenAddresses listenAddresses;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @Value("${automation.environment:Dev}")
    private String environment;

    /** Tells whether API-key security is switched on ({@code securityEnabled}); reachable without a key. */
    @GetMapping("/auth-status")
    public Map<String, Object> authStatus() {
        return Map.of("securityEnabled", securityEnabled);
    }

    /**
     * Health check without a key: {@code UP}, {@code DEGRADED} (Docker expected but not answering) or {@code DOWN} (database unreachable).
     * The components list database response time and Docker state.
     */
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

        // No Docker set up is a normal state, not a disturbed operation (a0e85182);
        // DEGRADED only if Docker should be there and does not respond.
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
     * Returns the heap usage as exactly three numbers, without an API key (c2cda5ed). The performance
     * audit measures memory with it without opening all of {@code /actuator/metrics}, which reveals
     * routes and load profile and stays behind the key. The source is the MemoryMXBean, not Micrometer:
     * {@code jvm.memory.max} sums over pools whose maximum is undefined ({@code -1}). Without a known heap
     * maximum, maximum and percentage are {@code null} rather than guessed; the percentage has one decimal.
     */
    @GetMapping("/jvm-memory")
    public Map<String, Object> jvmMemory() {
        return jvmMemory(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage());
    }

    /** Without a known maximum ({@code -1}) maximum and share stay empty instead of invented. */
    static Map<String, Object> jvmMemory(MemoryUsage heap) {
        long used = heap.getUsed();
        Long max = heap.getMax() > 0 ? heap.getMax() : null;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("jvm_memory_used_bytes", used);
        result.put("jvm_memory_max_bytes", max);
        result.put("jvm_heap_used_percent", max == null ? null : Math.round(used * 1000.0 / max) / 10.0);
        return result;
    }

    /**
     * Field limits generous enough for a real browser error (a React component stack included) and a hard
     * stop for a bloated report (TODO 3af3ac13). The client sends fire-and-forget and ignores the status,
     * so a 400 here cannot turn into a new error report.
     */
    record ClientErrorReport(
            @Size(max = 10_000) String message,
            @Size(max = 100_000) String stack,
            @Size(max = 100) String source,
            @Size(max = 8_192) String url,
            @Size(max = 64) String timestamp) {}

    /** Accepts an error report from the browser client and writes it to the backend log (202). */
    @PostMapping("/client-errors")
    public ResponseEntity<Void> reportClientError(@Valid @RequestBody ClientErrorReport report) {
        String firstStackLine = report.stack() != null ? report.stack().lines().findFirst().orElse("") : "no stack";
        log.warn("[CLIENT] {} | source={} url={} | {}",
                logField(report.message(), LOG_MESSAGE_MAX),
                logField(report.source(), LOG_SOURCE_MAX),
                logField(report.url(), LOG_URL_MAX),
                logField(firstStackLine, LOG_STACK_MAX));
        return ResponseEntity.accepted().build();
    }

    /**
     * One log-safe line of at most {@code max} characters (plus "…" when cut): every run of control
     * characters – CR, LF, TAB, ESC, NEL –, of format characters such as bidi overrides and of Unicode
     * line/paragraph separators becomes one space, so a value cannot start a forged log line, reorder one
     * visually or steer a terminal. A surrogate pair is never cut in half.
     */
    static String logField(String value, int max) {
        if (value == null) return null;
        String line = LOG_BREAKING_CHARS.matcher(value).replaceAll(" ");
        if (line.length() <= max) return line;
        int end = max > 0 && Character.isHighSurrogate(line.charAt(max - 1)) ? max - 1 : max;
        return line.substring(0, end) + "…";
    }

    /** Returns name, version, build, Java version, runtime mode, environment and the rate-limit figures; reachable without a key. */
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
     * Returns where the backend can be reached — address, port, this machine only or the network — and the
     * switch that changes it; needs the API key, unlike {@code /info}.
     */
    @GetMapping("/listen")
    public ListenAddresses.Report listen() {
        return listenAddresses.report();
    }

    /**
     * Rate-limit figures of this instance for the browser client: its 429 backoff should not
     * guess the numbers but obtain them from the instance it runs against (37b98c97).
     * The endpoint is deliberately public — nothing here that a 429 does not reveal anyway.
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
