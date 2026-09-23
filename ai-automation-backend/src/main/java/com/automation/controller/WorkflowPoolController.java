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

@RestController
@RequestMapping("/api/v1/pool")
@RequiredArgsConstructor
public class WorkflowPoolController {

    private final WorkflowPoolService poolService;

    /** @param locale {@code de}/{@code en}: language of the steps of bilingual pool files; without it English */
    @GetMapping
    public List<PoolWorkflowEntry> listAll(@RequestParam(required = false) String locale) {
        return poolService.listAll(locale);
    }

    @GetMapping("/{id}")
    public PoolWorkflowEntry getById(@PathVariable String id, @RequestParam(required = false) String locale) {
        return poolService.findById(id, locale);
    }

    @PostMapping("/{id}/install")
    public ResponseEntity<Map<String, Object>> install(
            @PathVariable String id,
            @Valid @RequestBody InstallPoolWorkflowRequest request) {
        WorkflowPoolService.InstallResult result = poolService.install(id, request);
        return ResponseEntity.ok(Map.of(
                "workflowId", result.workflow().getId(),
                "workflowName", result.workflow().getName(),
                "created", result.created()
        ));
    }

    /**
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
