package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.service.system.TailscaleService;
import com.github.dockerjava.api.DockerClient;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Map;

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
        } catch (Exception e) {
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
}
