package com.automation.controller;

import com.automation.dto.request.ApproveTodosRequest;
import com.automation.dto.request.ApproveExecutionRequest;
import com.automation.dto.request.ExecuteCommandRequest;
import com.automation.service.todo.PendingTodoApprovalService;
import com.automation.service.execution.ExecutionCommandService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.dto.response.PageResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.executor.ApprovalRequest;
import com.automation.service.workflow.RunDeletionService;
import com.automation.service.workflow.WorkflowExecutor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowExecutor workflowExecutor;
    private final ExecutionQueryService executionQueryService;
    private final ExecutionCommandService executionCommandService;
    private final PendingTodoApprovalService pendingTodoApprovalService;
    private final RunDeletionService runDeletionService;

    @GetMapping
    public PageResponse<WorkflowRunResponse> listExecutions(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String projectId,
            @RequestParam(required = false) String search) {

        return executionQueryService.listExecutionResponses(page, size, status, workflowId, projectId, search);
    }

    @GetMapping("/{id}")
    public ResponseEntity<WorkflowRunResponse> getExecution(@PathVariable String id) {
        return ResponseEntity.ok(executionQueryService.getExecutionResponse(id));
    }

    /**
     * Loescht einen Lauf samt Schritten und den losen Verweisen darauf.
     *
     * <p>409, wenn der Lauf noch aktiv ist oder ein Audit-Bericht an ihm haengt.
     * TODOs und Audit-Daten werden nie mitgeloescht — siehe {@link RunDeletionService}.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteExecution(@PathVariable String id) {
        runDeletionService.delete(id);
        return ResponseEntity.noContent().build();
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
    public ResponseEntity<WorkflowRunResponse> retryExecution(@PathVariable String id) {
        return ResponseEntity.ok(executionCommandService.retry(id));
    }

    @PostMapping("/{id}/resume-from-step")
    public ResponseEntity<WorkflowRunResponse> resumeFromStep(@PathVariable String id) {
        return ResponseEntity.ok(executionCommandService.resumeFromStep(id));
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
        pendingTodoApprovalService.approvePendingTodos(id, request.approved());
        return ResponseEntity.ok().build();
    }

    @PatchMapping("/{id}/notes")
    public ResponseEntity<WorkflowRunResponse> updateNotes(
            @PathVariable String id,
            @RequestBody Map<String, String> body) {
        return ResponseEntity.ok(executionCommandService.updateNotes(id, body.get("notes")));
    }

    @PostMapping("/{id}/command")
    public ResponseEntity<Map<String, String>> executeCommand(
            @PathVariable String id,
            @Valid @RequestBody ExecuteCommandRequest request) {
        String output = workflowExecutor.executeAdHocCommand(id, request.command());
        return ResponseEntity.ok(Map.of("output", output));
    }
}
