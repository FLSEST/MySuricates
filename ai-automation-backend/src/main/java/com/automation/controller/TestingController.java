package com.automation.controller;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.TestingStatusResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.staging.TestingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RequiresModule(ModuleCatalog.STAGING)
@RestController
@RequestMapping("/api/v1/testing")
@RequiredArgsConstructor
@Slf4j
public class TestingController {

    private final TestingService testingService;

    @GetMapping("/status")
    public TestingStatusResponse status() {
        return testingService.getStatus();
    }

    @PostMapping("/start")
    public ResponseEntity<Void> start() {
        testingService.start();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        testingService.stop();
        return ResponseEntity.ok().build();
    }

    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        testingService.resetDatabase();
        return ResponseEntity.ok().build();
    }

    @GetMapping("/sync-preview")
    public SyncPreview syncPreview() {
        return testingService.syncPreview();
    }

    @PostMapping("/sync-definitions")
    public SyncResult syncDefinitions(@RequestBody(required = false) SyncDefinitionsRequest request) {
        return testingService.syncDefinitions(request);
    }

    @PostMapping("/report-result")
    public ResponseEntity<Void> reportResult(@RequestBody Map<String, String> body) {
        String workflowName = body.get("workflowName");
        String projectName = body.get("projectName");
        String status = body.get("status");
        if (workflowName == null || status == null) {
            return ResponseEntity.badRequest().build();
        }
        testingService.reportTestResult(workflowName, projectName, status);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/pull-changes")
    public SyncResult pullChanges() {
        return testingService.pullChanges();
    }
}
