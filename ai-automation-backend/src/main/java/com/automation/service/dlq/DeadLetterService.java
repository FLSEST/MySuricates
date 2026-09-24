package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
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
     * Legt fuer jeden gescheiterten Run einen DLQ-Eintrag an. Der Eintrag entsteht ueber das
     * Abschluss-Event statt ueber einen Aufruf aus {@code RunCompletionHandler}, damit
     * service.workflow nicht an service.dlq haengt (Paketzyklus). Das Event feuert fuer jeden
     * terminalen Run — also auch fuer Laeufe, die an einer Ausnahme statt an einem Schritt scheitern.
     *
     * <p>Synchroner Listener: ein Fehler hier wird geschluckt, sonst schnitte er die folgenden
     * Listener desselben Events ab.
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
        // WorkflowExecutor schreibt den stoppenden Schritt als "step" in den Run-Fehler.
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
                .orElseThrow(() -> new IllegalArgumentException("DLQ entry not found: " + id));
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
