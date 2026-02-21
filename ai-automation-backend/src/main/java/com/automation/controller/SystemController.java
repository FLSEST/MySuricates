package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
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
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemController {

    private final DockerClient dockerClient;
    private final TailscaleService tailscaleService;
    private final DataSource dataSource;

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${app.version:0.1.0-SNAPSHOT}")
    private String appVersion;

    @Value("${app.build-timestamp:unknown}")
    private String buildTimestamp;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

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

        return Map.of(
                "name", "AI Automation Platform",
                "version", version,
                "buildType", buildType,
                "buildTimestamp", buildTimestamp,
                "java", System.getProperty("java.version"),
                "tailscale", tailscaleService.isConfigEnabled() ? "CONFIGURED" : "DISABLED"
        );
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
        // Extract DB path from JDBC URL like "jdbc:sqlite:./data/automation.db?..."
        String url = datasourceUrl;
        if (url.startsWith("jdbc:sqlite:")) {
            url = url.substring("jdbc:sqlite:".length());
        }
        int queryIdx = url.indexOf('?');
        if (queryIdx > 0) {
            url = url.substring(0, queryIdx);
        }

        Path dbPath = Path.of(url).toAbsolutePath().normalize();
        if (!Files.exists(dbPath)) {
            return ResponseEntity.notFound().build();
        }

        // Flush WAL to main database file before copying
        try (var conn = dataSource.getConnection(); var stmt = conn.createStatement()) {
            stmt.execute("PRAGMA wal_checkpoint(FULL)");
        } catch (Exception e) {
            log.warn("WAL checkpoint before backup failed: {}", e.getMessage());
        }

        // Copy to temp file (WAL-safe snapshot)
        Path tempFile = Files.createTempFile("autopilot-backup-", ".db");
        Files.copy(dbPath, tempFile, StandardCopyOption.REPLACE_EXISTING);

        // Also copy WAL and SHM if they exist (for consistency)
        Path walPath = Path.of(dbPath + "-wal");
        Path shmPath = Path.of(dbPath + "-shm");
        if (Files.exists(walPath)) {
            Files.copy(walPath, Path.of(tempFile + "-wal"), StandardCopyOption.REPLACE_EXISTING);
        }
        if (Files.exists(shmPath)) {
            Files.copy(shmPath, Path.of(tempFile + "-shm"), StandardCopyOption.REPLACE_EXISTING);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm"));
        String filename = "autopilot-backup-" + timestamp + ".db";

        InputStreamResource resource = new InputStreamResource(new FileInputStream(tempFile.toFile())) {
            @Override
            public InputStream getInputStream() throws IOException {
                return new FileInputStream(tempFile.toFile()) {
                    @Override
                    public void close() throws IOException {
                        super.close();
                        Files.deleteIfExists(tempFile);
                        Files.deleteIfExists(Path.of(tempFile + "-wal"));
                        Files.deleteIfExists(Path.of(tempFile + "-shm"));
                    }
                };
            }
        };

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .contentLength(Files.size(tempFile))
                .body(resource);
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
