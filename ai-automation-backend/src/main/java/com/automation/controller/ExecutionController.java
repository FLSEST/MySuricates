package com.automation.controller;

import com.automation.dto.request.ApproveExecutionRequest;
import com.automation.dto.request.ExecuteCommandRequest;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.exception.ResourceNotFoundException;
import com.automation.executor.ApprovalRequest;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.automation.entity.RunStatus;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final WorkflowMapper workflowMapper;

    @GetMapping
    public PageResponse<WorkflowRunResponse> listExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String projectId) {

        Pageable pageable = PageRequest.of(page, size);
        Page<com.automation.entity.WorkflowRun> result;

        RunStatus runStatus = null;
        if (status != null && !status.isBlank()) {
            runStatus = RunStatus.valueOf(status.toUpperCase());
        }

        if (workflowId != null && !workflowId.isBlank()) {
            result = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId, pageable);
        } else if (projectId != null && !projectId.isBlank()) {
            if (runStatus != null) {
                result = runRepository.findByStatusAndProjectIdOrderByStartedAtDesc(runStatus, projectId, pageable);
            } else {
                result = runRepository.findByProjectIdOrderByStartedAtDesc(projectId, pageable);
            }
        } else if (runStatus != null) {
            result = runRepository.findByStatusOrderByStartedAtDesc(runStatus, pageable);
        } else {
            result = runRepository.findByOrderByStartedAtDesc(pageable);
        }

        return new PageResponse<>(
                workflowMapper.toRunResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowRunResponse> getExecution(@PathVariable String id) {
        var run = runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution", id));
        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    @PostMapping("/{id}/pause")
    public ResponseEntity<Void> pauseExecution(@PathVariable String id) {
        workflowExecutor.pauseExecution(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/resume")
    public ResponseEntity<Void> resumeExecution(@PathVariable String id) {
        workflowExecutor.resumeExecution(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<Void> cancelExecution(@PathVariable String id) {
        workflowExecutor.cancelExecution(id);
        return ResponseEntity.ok().build();
    }

    @PostMapping("/{id}/approve")
    public ResponseEntity<Void> approveExecution(
            @PathVariable String id,
            @Valid @RequestBody ApproveExecutionRequest request) {
        workflowExecutor.resolveApproval(id, request.answer());
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{id}/approval")
    public ResponseEntity<Map<String, Object>> getPendingApproval(@PathVariable String id) {
        ApprovalRequest request = workflowExecutor.getPendingApproval(id);
        if (request == null) {
            return ResponseEntity.ok(Map.of("pending", false));
        }
        return ResponseEntity.ok(Map.of(
                "pending", true,
                "blockId", request.getBlockId(),
                "question", request.getQuestion(),
                "options", request.getOptions(),
                "stepIndex", request.getStepIndex()
        ));
    }

    @PostMapping("/{id}/command")
    public ResponseEntity<Map<String, String>> executeCommand(
            @PathVariable String id,
            @Valid @RequestBody ExecuteCommandRequest request) {
        String output = workflowExecutor.executeAdHocCommand(id, request.command());
        return ResponseEntity.ok(Map.of("output", output));
    }
}
