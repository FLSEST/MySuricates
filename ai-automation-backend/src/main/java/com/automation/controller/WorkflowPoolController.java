package com.automation.controller;

import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.pool.WorkflowPoolService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/pool")
@RequiredArgsConstructor
public class WorkflowPoolController {

    private final WorkflowPoolService poolService;

    @GetMapping
    public List<PoolWorkflowEntry> listAll() {
        return poolService.listAll();
    }

    @GetMapping("/{id}")
    public PoolWorkflowEntry getById(@PathVariable String id) {
        return poolService.findById(id);
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
}
