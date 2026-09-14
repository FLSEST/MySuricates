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

@RequiresModule(ModuleCatalog.STAGING)
@RestController
@RequestMapping("/api/v1/staging")
@RequiredArgsConstructor
@Slf4j
public class StagingController {

    private final StagingService stagingService;

    @GetMapping("/status")
    public StagingStatusResponse status() {
        return stagingService.getStatus();
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start() {
        stagingService.start();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        stagingService.stop();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/promote")
    public ResponseEntity<Void> promote() {
        stagingService.promote();
        return ResponseEntity.accepted().build();
    }

    @PostMapping("/promote/cancel")
    public ResponseEntity<Void> cancelPromote() {
        stagingService.cancelPromote();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sync-preview")
    public SyncPreview syncPreview() {
        return stagingService.syncPreview();
    }

    @PostMapping("/sync-definitions")
    public SyncResult syncDefinitions(@RequestBody(required = false) SyncDefinitionsRequest request) {
        return stagingService.syncDefinitions(request);
    }
}
