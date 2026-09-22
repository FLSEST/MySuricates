package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.system.FileBrowserService;
import com.automation.service.system.IntegrationSnippetService;
import com.automation.service.system.TailscaleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Zugriffs-Oberfläche unter /api/v1/system: Remote Access (Tailscale) und lokales Dateisystem. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemAccessController {

    private final TailscaleService tailscaleService;
    private final FileBrowserService fileBrowserService;
    private final IntegrationSnippetService integrationSnippetService;

    @PostMapping("/remote-access/enable")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<RemoteAccessResponse> enableRemoteAccess() {
        RemoteAccessResponse response = tailscaleService.enable();
        return ResponseEntity.ok(response);
    }

    @PostMapping("/remote-access/disable")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<Void> disableRemoteAccess() {
        tailscaleService.disable();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/remote-access/status")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<RemoteAccessResponse> remoteAccessStatus() {
        RemoteAccessResponse response = tailscaleService.getStatus();
        return ResponseEntity.ok(response);
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

    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @GetMapping(value = "/integration-snippet", produces = "text/markdown")
    public ResponseEntity<String> integrationSnippet(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) String locale) {
        return ResponseEntity.ok(integrationSnippetService.render(projectPath, locale));
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
