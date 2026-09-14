package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.TriggerSource;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Retries a dead-letter entry: best-effort re-execution of the original workflow with the
 * failed run's input context (if both still exist), then marks the entry as retried.
 * Extracted from {@code DeadLetterController} (layer rule: Controller → Service → Repository).
 */
@Service
@RequiredArgsConstructor
public class DeadLetterRetryService {

    private final DeadLetterService dlqService;
    private final WorkflowRepository workflowRepository;
    private final WorkflowRunRepository runRepository;
    private final WorkflowExecutor workflowExecutor;

    public DeadLetterEntry retry(String entryId) {
        DeadLetterEntry entry = dlqService.findById(entryId);
        if (entry.getWorkflowId() != null && entry.getWorkflowRunId() != null) {
            workflowRepository.findById(entry.getWorkflowId()).ifPresent(workflow ->
                    runRepository.findById(entry.getWorkflowRunId()).ifPresent(originalRun ->
                            workflowExecutor.execute(workflow, originalRun.getInputContext(), TriggerSource.RETRY)));
        }
        return dlqService.markRetried(entryId);
    }
}
