package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.ErrorCode;
import com.automation.exception.ErrorResponse;
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

/** Access endpoints under /api/v1/system: remote access (Tailscale) and the local file system. */
@RestController
@RequestMapping("/api/v1/system")
@RequiredArgsConstructor
@Slf4j
public class SystemAccessController {

    private final TailscaleService tailscaleService;
    private final FileBrowserService fileBrowserService;
    private final IntegrationSnippetService integrationSnippetService;

    /**
     * Brings Tailscale up on the host and returns the Tailscale IP and hostname.
     * A failing Tailscale command, or an instance running in a container, is 503.
     */
    @PostMapping("/remote-access/enable")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<RemoteAccessResponse> enableRemoteAccess() {
        RemoteAccessResponse response = tailscaleService.enable();
        return ResponseEntity.ok(response);
    }

    /** Takes Tailscale down on the host; a failing command or a container instance is 503. */
    @PostMapping("/remote-access/disable")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<Void> disableRemoteAccess() {
        tailscaleService.disable();
        return ResponseEntity.ok().build();
    }

    /** Returns whether Tailscale is up, with IP and hostname, and whether this instance runs in a container. */
    @GetMapping("/remote-access/status")
    @RequiresModule(ModuleCatalog.REMOTE_ACCESS)
    public ResponseEntity<RemoteAccessResponse> remoteAccessStatus() {
        RemoteAccessResponse response = tailscaleService.getStatus();
        return ResponseEntity.ok(response);
    }

    record OpenPathRequest(String path) {}

    /**
     * Opens a file or directory ({@code path}) in the desktop's file manager on the host.
     * An empty, missing or system path ({@code /etc}, {@code /proc}, ...) is 400.
     */
    @PostMapping("/open-path")
    public ResponseEntity<?> openPath(@RequestBody OpenPathRequest request) {
        try {
            fileBrowserService.openPath(request.path());
            return ResponseEntity.ok(Map.of("status", "ok"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        } catch (IOException e) {
            log.error("Failed to open path: {}", request.path(), e);
            return ResponseEntity.internalServerError().body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, e.getMessage()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.internalServerError()
                    .body(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Interrupted while opening path"));
        }
    }

    /**
     * Renders the base onboarding text as a Markdown snippet to paste into a project, e.g. into an {@code AGENTS.md}.
     * With a {@code projectPath} of a known project it uses that project's id and lists its workflows; {@code locale} picks the
     * language (default {@code en}).
     */
    @RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
    @GetMapping(value = "/integration-snippet", produces = "text/markdown")
    public ResponseEntity<String> integrationSnippet(
            @RequestParam(required = false) String projectPath,
            @RequestParam(required = false) String locale) {
        return ResponseEntity.ok(integrationSnippetService.render(projectPath, locale));
    }

    /**
     * Lists the subdirectories of {@code path} (default: the home directory) without hidden ones, plus the parent path.
     * The root and system directories are refused with 400, as is a path that is no readable directory.
     */
    @GetMapping("/browse")
    public ResponseEntity<?> browseDirectory(
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
            return ResponseEntity.badRequest().body(ErrorResponse.from(e, ErrorCode.INVALID_ARGUMENT));
        } catch (IOException e) {
            return ResponseEntity.badRequest()
                    .body(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, "Cannot read directory: " + e.getMessage()));
        }
    }
}
