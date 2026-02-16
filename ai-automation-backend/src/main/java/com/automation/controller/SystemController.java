package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.service.system.TailscaleService;
import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.stream.Stream;

@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
public class SystemController {

    private final DockerClient dockerClient;
    private final TailscaleService tailscaleService;

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
        return Map.of(
                "name", "AI Automation Platform",
                "version", "0.1.0",
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
