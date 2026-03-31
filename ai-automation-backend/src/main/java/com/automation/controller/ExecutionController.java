package com.automation.controller;

import com.automation.dto.request.ApproveTodosRequest;
import com.automation.dto.request.ApproveExecutionRequest;
import com.automation.dto.request.ExecuteCommandRequest;
import com.automation.service.todo.AutoTodoService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.repository.WorkflowRunRepository;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.executor.ApprovalRequest;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowService workflowService;
    private final ExecutionQueryService executionQueryService;
    private final WorkflowMapper workflowMapper;
    private final AutoTodoService autoTodoService;
    private final WorkflowRunRepository workflowRunRepository;

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<WorkflowRunResponse> listExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String projectId) {

        Page<com.automation.entity.WorkflowRun> result =
                executionQueryService.listExecutions(page, size, status, workflowId, projectId);

        return new PageResponse<>(
                workflowMapper.toRunResponseList(result.getContent()),
                result.getNumber(),
                result.getSize(),
                result.getTotalElements(),
                result.getTotalPages()
        );
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<WorkflowRunResponse> getExecution(@PathVariable String id) {
        var run = executionQueryService.findById(id);
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

    @PostMapping("/{id}/retry")
    @Transactional
    public ResponseEntity<WorkflowRunResponse> retryExecution(@PathVariable String id) {
        var run = executionQueryService.findById(id);
        var workflow = workflowService.findByIdEager(run.getWorkflow().getId());
        var newRun = workflowExecutor.execute(workflow, run.getInputContext());
        return ResponseEntity.ok(workflowMapper.toRunResponse(newRun));
    }

    @PostMapping("/{id}/resume-from-step")
    @Transactional
    public ResponseEntity<WorkflowRunResponse> resumeFromStep(@PathVariable String id) {
        var resumedRun = workflowExecutor.resumeFromStep(id);
        return ResponseEntity.ok(workflowMapper.toRunResponse(resumedRun));
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
        Map<String, Object> response = new java.util.HashMap<>();
        response.put("pending", true);
        response.put("blockId", request.getBlockId());
        response.put("question", request.getQuestion());
        response.put("options", request.getOptions());
        response.put("stepIndex", request.getStepIndex());
        response.put("type", request.getType());

        if ("human-input".equals(request.getType())) {
            response.put("inputType", request.getInputType());
            response.put("defaultValue", request.getDefaultValue());
            response.put("variableName", request.getVariableName());
            response.put("required", request.isRequired());
            response.put("validationPattern", request.getValidationPattern());
        }
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/todos/approve")
    public ResponseEntity<Void> approveTodos(
            @PathVariable String id,
            @RequestBody ApproveTodosRequest request) {
        autoTodoService.approvePendingTodos(id, request.approved());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/notes")
    @Transactional
    public ResponseEntity<WorkflowRunResponse> updateNotes(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        var run = executionQueryService.findById(id);
        var metadata = run.getMetadata() != null ? new HashMap<>(run.getMetadata()) : new HashMap<String, Object>();
        String notes = body.get("notes");
        if (notes != null && !notes.isBlank()) {
            metadata.put("notes", notes);
        } else {
            metadata.remove("notes");
        }
        run.setMetadata(metadata);
        workflowRunRepository.save(run);
        return ResponseEntity.ok(workflowMapper.toRunResponse(run));
    }

    @PostMapping("/{id}/command")
    public ResponseEntity<Map<String, String>> executeCommand(
            @PathVariable String id,
            @Valid @RequestBody ExecuteCommandRequest request) {
        String output = workflowExecutor.executeAdHocCommand(id, request.command());
        return ResponseEntity.ok(Map.of("output", output));
    }
}
