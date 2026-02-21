package com.automation.controller;

import com.automation.dto.response.WorkflowRunResponse;
import com.automation.exception.ResourceNotFoundException;
import com.automation.executor.ApprovalRequest;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import com.automation.entity.RunStatus;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final WorkflowMapper workflowMapper;

    @GetMapping
    public List<WorkflowRunResponse> listExecutions(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String workflowId,
            @RequestParam(required = false) String projectId) {
        List<com.automation.entity.WorkflowRun> runs;

        RunStatus runStatus = null;
        if (status != null && !status.isBlank()) {
            runStatus = RunStatus.valueOf(status.toUpperCase());
        }

        if (workflowId != null && !workflowId.isBlank()) {
            runs = runRepository.findByWorkflowIdOrderByStartedAtDesc(workflowId);
            if (runStatus != null) {
                RunStatus finalStatus = runStatus;
                runs = runs.stream().filter(r -> r.getStatus() == finalStatus).toList();
            }
        } else if (projectId != null && !projectId.isBlank()) {
            if (runStatus != null) {
                runs = runRepository.findByStatusAndProjectIdOrderByStartedAtDesc(runStatus, projectId);
            } else {
                runs = runRepository.findByProjectIdOrderByStartedAtDesc(projectId);
            }
        } else if (runStatus != null) {
            runs = runRepository.findByStatusOrderByStartedAtDesc(runStatus);
        } else {
            runs = runRepository.findAllByOrderByStartedAtDesc();
        }

        return workflowMapper.toRunResponseList(runs);
    }

    @GetMapping("/{id}")
    public WorkflowRunResponse getExecution(@PathVariable String id) {
        var run = runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Execution", id));
        return workflowMapper.toRunResponse(run);
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
            @RequestBody Map<String, String> body) {
        String answer = body.get("answer");
        if (answer == null || answer.isBlank()) {
            return ResponseEntity.badRequest().build();
        }
        workflowExecutor.resolveApproval(id, answer);
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
            @RequestBody Map<String, String> body) {
        String command = body.get("command");
        if (command == null || command.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Command is required"));
        }
        String output = workflowExecutor.executeAdHocCommand(id, command);
        return ResponseEntity.ok(Map.of("output", output));
    }
}
