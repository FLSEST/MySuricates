package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.system.DatabaseBackupService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final DockerClient dockerClient;
    private final TailscaleService tailscaleService;
    private final DatabaseBackupService databaseBackupService;
    private final AppSettingsRepository appSettingsRepository;
    private final AutoRepairService autoRepairService;
    private final DataSource dataSource;
    private final SystemIdentityService systemIdentity;
    private final AiProviderRegistry aiProviderRegistry;

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
        result.put("tailscale", tailscaleService.isConfigEnabled() ? "CONFIGURED" : "DISABLED");
        result.put("environment", environment);
        return result;
    }

    @GetMapping("/ai-providers")
    public Map<String, Object> aiProviders() {
        return Map.of(
                "providers", aiProviderRegistry.getAvailableProviderIds(),
                "default", aiProviderRegistry.getDefaultProviderId()
        );
    }

    @PostMapping("/repair-failed")
    public ResponseEntity<Map<String, Object>> repairAllFailed(
            @RequestParam(defaultValue = "false") boolean force) {
        if (!autoRepairService.isTestInstanceHealthy()) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", "Test instance is not healthy"));
        }

        int queued = autoRepairService.repairAllFailed(force);
        return ResponseEntity.accepted().body(Map.of(
                "status", "repair_started",
                "workflowsQueued", queued,
                "force", force));
    }

    @PostMapping("/remote-access/enable")
    public ResponseEntity<RemoteAccessResponse> enableRemoteAccess() {
        RemoteAccessResponse response = tailscaleService.enable();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/remote-access/disable")
    public ResponseEntity<Void> disableRemoteAccess() {
        tailscaleService.disable();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/remote-access/status")
    public ResponseEntity<RemoteAccessResponse> remoteAccessStatus() {
        RemoteAccessResponse response = tailscaleService.getStatus();
        return ResponseEntity.ok(response);
    }

    @GetMapping("/backup")
    public ResponseEntity<Resource> backupDatabase() throws IOException {
        Path backupPath = databaseBackupService.createBackup();
        String filename = backupPath.getFileName().toString();

        InputStreamResource resource = new InputStreamResource(new FileInputStream(backupPath.toFile()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(backupPath))
                .body(resource);
    }

    @GetMapping("/backups")
    public List<DatabaseBackupService.BackupInfo> listBackups() throws IOException {
        return databaseBackupService.listBackups();
    }

    @GetMapping("/backups/{filename}")
    public ResponseEntity<Resource> downloadBackup(@PathVariable String filename) throws IOException {
        Path file = databaseBackupService.getBackupFile(filename);
        if (file == null) {
            return ResponseEntity.notFound().build();
        }

        InputStreamResource resource = new InputStreamResource(new FileInputStream(file.toFile()));

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(file))
                .body(resource);
    }

    private static final Set<String> ALLOWED_SETTINGS = Set.of("auto-todo-default", "auto-repair-default", "default-ai-provider");

    @GetMapping("/settings/{key}")
    public ResponseEntity<Map<String, String>> getSetting(@PathVariable String key) {
        if (!ALLOWED_SETTINGS.contains(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = appSettingsRepository.get(key).orElse(null);
        return ResponseEntity.ok(Map.of("key", key, "value", value != null ? value : ""));
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<Void> setSetting(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!ALLOWED_SETTINGS.contains(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().build();
        }
        appSettingsRepository.set(key, value);
        return ResponseEntity.ok().build();
    }

    private static final Set<String> BLOCKED_PREFIXES = Set.of(
            "/etc", "/proc", "/sys", "/dev", "/var/run"
    );

    record OpenPathRequest(String path) {}

    @PostMapping("/open-path")
    public ResponseEntity<Map<String, String>> openPath(@RequestBody OpenPathRequest request) {
        if (request.path() == null || request.path().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Path must not be empty"));
        }

        Path targetPath = Path.of(request.path()).normalize().toAbsolutePath();
        String normalized = targetPath.toString();

        for (String prefix : BLOCKED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Access to system directory is restricted: " + prefix));
            }
        }

        if (!Files.exists(targetPath)) {
            return ResponseEntity.status(404).body(Map.of("error", "Path does not exist: " + normalized));
        }

        try {
            ProcessBuilder pb = new ProcessBuilder("xdg-open", normalized);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // Drain output to prevent blocking
            try (InputStream is = process.getInputStream()) {
                is.readAllBytes();
            }

            boolean exited = process.waitFor(3, TimeUnit.SECONDS);
            if (exited && process.exitValue() != 0) {
                log.warn("xdg-open exited with code {} for path: {}", process.exitValue(), normalized);
                return ResponseEntity.internalServerError().body(Map.of(
                        "error", "Failed to open path (exit code " + process.exitValue() + ")"));
            }
            // Either exited successfully or still running (file manager is open)
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IOException e) {
            log.error("Failed to open path: {}", normalized, e);
            return ResponseEntity.internalServerError().body(Map.of("error", "Failed to open path: " + e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(Map.of("error", "Interrupted while opening path"));
        }
    }

    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browseDirectory(
            @RequestParam(required = false) String path) {

        Path dirPath;
        if (path == null || path.isBlank()) {
            dirPath = Path.of(System.getProperty("user.home"));
        } else {
            dirPath = Path.of(path).normalize().toAbsolutePath();
        }

        String normalized = dirPath.toString();

        // Block sensitive system directories
        if (normalized.equals("/")) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot browse root directory"));
        }
        for (String prefix : BLOCKED_PREFIXES) {
            if (normalized.startsWith(prefix)) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "Access to system directory is restricted: " + prefix));
            }
        }

        if (!Files.isDirectory(dirPath)) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Path is not a directory: " + normalized));
        }

        List<Map<String, String>> directories = new ArrayList<>();
        try (Stream<Path> entries = Files.list(dirPath)) {
            entries.filter(Files::isDirectory)
                    .filter(p -> !p.getFileName().toString().startsWith("."))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase()))
                    .forEach(p -> directories.add(Map.of(
                            "name", p.getFileName().toString(),
                            "path", p.toAbsolutePath().toString()
                    )));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Cannot read directory: " + e.getMessage()));
        }

        Path parent = dirPath.getParent();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("current", normalized);
        result.put("parent", parent != null ? parent.toString() : null);
        result.put("directories", directories);

        return ResponseEntity.ok(result);
    }
}
