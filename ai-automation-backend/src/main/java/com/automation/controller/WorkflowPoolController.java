package com.automation.controller;

import com.automation.dto.request.ExportToPoolRequest;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.pool.WorkflowPoolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/** Workflow pool: list and install the shared workflow files of the pool directory, and export a workflow into it. */
@RestController
@RequestMapping("/api/v1/pool")
@RequiredArgsConstructor
public class WorkflowPoolController {

    private final WorkflowPoolService poolService;

    /**
     * Lists the workflows of the pool directory, sorted by English name; empty when the pool is switched off.
     *
     * @param locale {@code de}/{@code en}: language of the steps of bilingual pool files; without it English
     */
    @GetMapping
    public List<PoolWorkflowEntry> listAll(@RequestParam(required = false) String locale) {
        return poolService.listAll(locale);
    }

    /** Returns one pool workflow; {@code locale} as for the list. */
    @GetMapping("/{id}")
    public PoolWorkflowEntry getById(@PathVariable String id, @RequestParam(required = false) String locale) {
        return poolService.findById(id, locale);
    }

    /**
     * Installs a pool workflow into the project {@code projectId}, or updates the copy an earlier install left there.
     * The copy is also found under its name in the other language; {@code forceCreate} always creates a new one.
     * A pool file whose checksum does not match is refused with {@code 400}; the answer's {@code integrity} says
     * {@code VERIFIED} or {@code MISSING}.
     */
    @PostMapping("/{id}/install")
    public ResponseEntity<Map<String, Object>> install(
            @PathVariable String id,
            @Valid @RequestBody InstallPoolWorkflowRequest request) {
        WorkflowPoolService.InstallResult result = poolService.install(id, request);
        return ResponseEntity.ok(Map.of(
                "workflowId", result.workflow().getId(),
                "workflowName", result.workflow().getName(),
                "created", result.created(),
                // VERIFIED | MISSING (installed with a warning); a mismatch is a 400 (TODO b18939a6)
                "integrity", result.integrity().name()
        ));
    }

    /**
     * Writes the workflow as a new pool file and answers {@code 201}; instance bindings such as ids are removed
     * and listed in {@code sanitizedPaths}. Id keys ({@code projectId}, {@code workflowId}, ...) with a concrete value
     * and UUID values are dropped, placeholders like {@code {{ project_id }}} stay, and a UUID inside a text becomes
     * {@code REDACTED-UUID}. An unknown {@code scope} is 400.
     *
     * @param scope {@code repo}, {@code portable} oder {@code external} — Pool-Metadatum, ohne Angabe {@code portable}.
     *              Regelwerk: {@code docs/workflow-pool-kuratierung.md}.
     */
    @PostMapping("/export/{workflowId}")
    public ResponseEntity<ExportToPoolResponse> exportToPool(
            @PathVariable String workflowId,
            @RequestParam(required = false) String scope,
            @RequestBody(required = false) ExportToPoolRequest request) throws IOException {
        ExportToPoolResponse result = poolService.exportToPool(workflowId, request, scope);
        return ResponseEntity.status(HttpStatus.CREATED).body(result);
    }
}
