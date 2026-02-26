package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.DatabaseBackupService;
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

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
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

    @Value("${app.version:0.1.0-SNAPSHOT}")
    private String appVersion;

    @Value("${app.build-timestamp:unknown}")
    private String buildTimestamp;

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

        return Map.of(
                "status", "UP",
                "timestamp", Instant.now().toString(),
                "docker", dockerAvailable ? "CONNECTED" : "UNAVAILABLE"
        );
    }

    @GetMapping("/info")
    public Map<String, Object> info() {
        String version = appVersion;
        String buildType = "RELEASE";
        int dashIdx = appVersion.indexOf('-');
        if (dashIdx > 0) {
            version = appVersion.substring(0, dashIdx);
            buildType = appVersion.substring(dashIdx + 1);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", "MySuricates");
        result.put("version", version);
        result.put("buildType", buildType);
        result.put("buildTimestamp", buildTimestamp);
        result.put("java", System.getProperty("java.version"));
        result.put("tailscale", tailscaleService.isConfigEnabled() ? "CONFIGURED" : "DISABLED");
        result.put("environment", environment);
        return result;
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

    private static final Set<String> ALLOWED_SETTINGS = Set.of("auto-todo-default");

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
