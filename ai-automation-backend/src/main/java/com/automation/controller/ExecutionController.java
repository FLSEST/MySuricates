package com.automation.controller;

import com.automation.dto.response.WorkflowRunResponse;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/executions")
@RequiredArgsConstructor
public class ExecutionController {

    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final WorkflowMapper workflowMapper;

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
