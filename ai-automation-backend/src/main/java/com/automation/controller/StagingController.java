package com.automation.controller;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.staging.StagingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/** The local Stable environment (Docker container): status, start/stop, promote and definition sync from this instance. */
@RequiresModule(ModuleCatalog.STAGING)
@RestController
@RequestMapping("/api/v1/staging")
@RequiredArgsConstructor
@Slf4j
public class StagingController {

    private final StagingService stagingService;

    /** Returns whether the Stable container runs and is healthy, the promote progress and the Stable version. */
    @GetMapping("/status")
    public StagingStatusResponse status() {
        return stagingService.getStatus();
    }

    /**
     * Starts the Stable container and waits until it is healthy.
     * Refused with 409 when no Stable frontend has been built yet; run a promote first.
     */
    @PostMapping("/start")
    public ResponseEntity<Void> start() {
        stagingService.start();
        return ResponseEntity.ok().build();
    }

    /** Stops the Stable container. */
    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        stagingService.stop();
        return ResponseEntity.ok().build();
    }

    /**
     * Starts a promote in the background (202): stop Stable, build backend, frontend and image, start it again.
     * While a promote is running another one is 409; progress is in {@code GET /api/v1/staging/status}.
     */
    @PostMapping("/promote")
    public ResponseEntity<Void> promote() {
        stagingService.promote();
        return ResponseEntity.accepted().build();
    }

    /** Cancels a running promote and kills its current build step. */
    @PostMapping("/promote/cancel")
    public ResponseEntity<Void> cancelPromote() {
        stagingService.cancelPromote();
        return ResponseEntity.ok().build();
    }

    /** Previews which projects, workflows, skills and chains a sync to Stable would transfer; 409 while Stable is not healthy. */
    @GetMapping("/sync-preview")
    public SyncPreview syncPreview() {
        return stagingService.syncPreview();
    }

    /**
     * Transfers projects, workflows, skills and chains from this instance to Stable; an optional body narrows the selection by ids.
     * Refused with 409 while Stable is not healthy.
     */
    @PostMapping("/sync-definitions")
    public SyncResult syncDefinitions(@RequestBody(required = false) SyncDefinitionsRequest request) {
        return stagingService.syncDefinitions(request);
    }
}
