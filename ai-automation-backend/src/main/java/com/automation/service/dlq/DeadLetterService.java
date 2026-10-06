package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ElementNotFoundException;
import com.automation.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class DeadLetterService {

    private final DeadLetterRepository repository;

    /**
     * Creates a DLQ entry for every failed run. The entry arises via the
     * completion event instead of a call from {@code RunCompletionHandler}, so that
     * service.workflow does not depend on service.dlq (package cycle). The event fires for every
     * terminal run — so also for runs that fail on an exception instead of on a step.
     *
     * <p>Synchronous listener: an error here is swallowed, otherwise it would cut off the following
     * listeners of the same event.
     */
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        if (event.getStatus() != RunStatus.FAILED) {
            return;
        }
        try {
            recordFailure(event);
        } catch (Exception e) {
            log.warn("Failed to record DLQ entry for run {}: {}", event.getRunId(), e.getMessage());
        }
    }

    @Transactional
    public DeadLetterEntry recordFailure(WorkflowRunCompletedEvent run) {
        // WorkflowExecutor writes the stopping step as "step" into the run error.
        Map<String, Object> error = run.getError();
        Integer failedStepIndex = error.get("step") instanceof Number step ? step.intValue() : null;
        String message = error.get("message") != null ? error.get("message").toString() : null;

        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId(run.getRunId())
                .workflowId(run.getWorkflowId())
                .workflowName(run.getWorkflowName())
                .errorMessage(message)
                .failedStepIndex(failedStepIndex)
                .retryCount(0)
                .lastFailureAt(Instant.now())
                .status(DeadLetterEntry.Status.OPEN)
                .build();

        DeadLetterEntry saved = repository.save(entry);
        log.info("Recorded DLQ entry {} for failed run {}", saved.getId(), run.getRunId());
        return saved;
    }

    public Page<DeadLetterEntry> list(DeadLetterEntry.Status status, Pageable pageable) {
        return status == null
                ? repository.findAllByOrderByLastFailureAtDesc(pageable)
                : repository.findByStatusOrderByLastFailureAtDesc(status, pageable);
    }

    public DeadLetterEntry findById(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ElementNotFoundException("DeadLetterEntry", id));
    }

    @Transactional
    public DeadLetterEntry markRetried(String id) {
        DeadLetterEntry entry = findById(id);
        entry.setRetryCount(entry.getRetryCount() + 1);
        entry.setStatus(DeadLetterEntry.Status.RETRIED);
        return repository.save(entry);
    }

    @Transactional
    public void discard(String id) {
        DeadLetterEntry entry = findById(id);
        entry.setStatus(DeadLetterEntry.Status.DISCARDED);
        repository.save(entry);
    }
}
