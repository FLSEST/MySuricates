package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.system.*;
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

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final DockerClient dockerClient;
    private final TailscaleService tailscaleService;
    private final DatabaseBackupService databaseBackupService;
    private final AutoRepairService autoRepairService;
    private final DataSource dataSource;
    private final SystemIdentityService systemIdentity;
    private final AiProviderRegistry aiProviderRegistry;
    private final ExecutionSettingsService executionSettingsService;
    private final SystemSettingsService systemSettingsService;
    private final FileBrowserService fileBrowserService;
    private final IntegrationSnippetService integrationSnippetService;

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

    @PostMapping("/vacuum")
    public ResponseEntity<Map<String, Object>> vacuumDatabase() {
        Map<String, Object> result = databaseBackupService.fullVacuum();
        if ("failed".equals(result.get("status"))) {
            return ResponseEntity.internalServerError().body(result);
        }
        return ResponseEntity.ok(result);
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

    @GetMapping("/settings/{key}")
    public ResponseEntity<Map<String, String>> getSetting(@PathVariable String key) {
        if (!systemSettingsService.isAllowedKey(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = systemSettingsService.getSetting(key).orElse("");
        return ResponseEntity.ok(Map.of("key", key, "value", value));
    }

    @PutMapping("/settings/{key}")
    public ResponseEntity<Void> setSetting(@PathVariable String key, @RequestBody Map<String, String> body) {
        if (!systemSettingsService.isAllowedKey(key)) {
            return ResponseEntity.badRequest().build();
        }
        String value = body.get("value");
        if (value == null) {
            return ResponseEntity.badRequest().build();
        }
        systemSettingsService.setSetting(key, value);
        return ResponseEntity.ok().build();
    }

    @GetMapping("/execution-config")
    public ResponseEntity<Map<String, Object>> getExecutionConfig() {
        return ResponseEntity.ok(executionSettingsService.getCurrentSettings());
    }

    @PutMapping("/execution-config")
    public ResponseEntity<?> updateExecutionConfig(@RequestBody Map<String, String> settings) {
        try {
            executionSettingsService.updateAll(settings);
            return ResponseEntity.ok(executionSettingsService.getCurrentSettings());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    record OpenPathRequest(String path) {}

    @PostMapping("/open-path")
    public ResponseEntity<Map<String, String>> openPath(@RequestBody OpenPathRequest request) {
        try {
            fileBrowserService.openPath(request.path());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            log.error("Failed to open path: {}", request.path(), e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError().body(Map.of("error", "Interrupted while opening path"));
        }
    }

    @GetMapping(value = "/integration-snippet", produces = "text/markdown")
    public ResponseEntity<String> integrationSnippet(
            @RequestParam(required = false) String projectPath) {
        return ResponseEntity.ok(integrationSnippetService.render(projectPath));
    }

    @GetMapping("/browse")
    public ResponseEntity<Map<String, Object>> browseDirectory(
            @RequestParam(required = false) String path) {
        try {
            FileBrowserService.BrowseResult result = fileBrowserService.browseDirectory(path);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("current", result.current());
            response.put("parent", result.parent());
            List<Map<String, String>> dirs = result.directories().stream()
                    .map(d -> Map.of("name", d.name(), "path", d.path()))
                    .toList();
            response.put("directories", dirs);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Cannot read directory: " + e.getMessage()));
        }
    }
}
