package com.automation.controller;

import com.automation.entity.DeadLetterEntry;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.dlq.DeadLetterService;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/dlq")
@RequiredArgsConstructor
public class DeadLetterController {

    private final DeadLetterService dlqService;
    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowExecutor workflowExecutor;

    @GetMapping
    public Page<DeadLetterEntry> list(
            @RequestParam(required = false) DeadLetterEntry.Status status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return dlqService.list(status, PageRequest.of(page, size));
    }

    @GetMapping("/{id}")
    public DeadLetterEntry get(@PathVariable String id) {
        return dlqService.findById(id);
    }

    @PostMapping("/{id}/retry")
    public ResponseEntity<DeadLetterEntry> retry(@PathVariable String id) {
        DeadLetterEntry entry = dlqService.findById(id);
        if (entry.getWorkflowId() != null && entry.getWorkflowRunId() != null) {
            // Best-effort: trigger a fresh execution of the original workflow with the
            // same input context as the failed run (if still available).
            workflowRepository.findById(entry.getWorkflowId()).ifPresent(workflow ->
                    runRepository.findById(entry.getWorkflowRunId()).ifPresent(originalRun ->
                            workflowExecutor.execute(workflow, originalRun.getInputContext())));
        }
        return ResponseEntity.ok(dlqService.markRetried(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> discard(@PathVariable String id) {
        dlqService.discard(id);
        return ResponseEntity.noContent().build();
    }
}
