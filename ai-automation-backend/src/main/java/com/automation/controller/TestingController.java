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

/** Controls the Test instance container: start, stop, reset, push definitions to it and pull changes back. */
@RequiresModule(ModuleCatalog.STAGING)
@RestController
@RequestMapping("/api/v1/testing")
@RequiredArgsConstructor
@Slf4j
public class TestingController {

    private final TestingService testingService;

    /** Reports whether the Test container is running and healthy, plus its version and build timestamp when healthy. */
    @GetMapping("/status")
    public TestingStatusResponse status() {
        return testingService.getStatus();
    }

    /**
     * Starts the Test container with this instance's API key and waits for its health check.
     * Returns 409 while no {@code stable-frontend/} build exists (promote first).
     */
    @PostMapping("/start")
    public ResponseEntity<Void> start() {
        testingService.start();
        return ResponseEntity.ok().build();
    }

    /** Stops the Test container. */
    @PostMapping("/stop")
    public ResponseEntity<Void> stop() {
        testingService.stop();
        return ResponseEntity.ok().build();
    }

    /**
     * Wipes the Test database volume, starts the container fresh and pushes all definitions to it.
     * Everything that existed only on the Test instance is lost.
     */
    @PostMapping("/reset")
    public ResponseEntity<Void> reset() {
        testingService.resetDatabase();
        return ResponseEntity.ok().build();
    }

    /** Lists which projects, workflows, skills and chains a sync would create or update on the Test instance. */
    @GetMapping("/sync-preview")
    public SyncPreview syncPreview() {
        return testingService.syncPreview();
    }

    /**
     * Copies definitions from this instance to the Test instance, after a backup there.
     * Without a body everything is synced; {@code projectIds}, {@code workflowIds}, {@code skillIds} and
     * {@code chainIds} narrow it down. Returns 409 while the Test instance is not healthy.
     */
    @PostMapping("/sync-definitions")
    public SyncResult syncDefinitions(@RequestBody(required = false) SyncDefinitionsRequest request) {
        return testingService.syncDefinitions(request);
    }

    /**
     * Records a test result as test status of the workflow with this name and project name.
     * {@code workflowName} and {@code status} are required (else 400); an unknown workflow is only logged.
     */
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

    /**
     * Takes instructions and model of the Test instance's workflows into the workflows with the same name and project here.
     * Returns 409 while the Test instance is not healthy.
     */
    @PostMapping("/pull-changes")
    public SyncResult pullChanges() {
        return testingService.pullChanges();
    }
}
