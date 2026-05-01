package com.automation.service.dlq;

import com.automation.entity.DeadLetterEntry;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.DeadLetterRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
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

    @Transactional
    public DeadLetterEntry recordFailure(WorkflowRun run) {
        Integer failedStepIndex = null;
        if (run.getSteps() != null) {
            failedStepIndex = run.getSteps().stream()
                    .filter(s -> s.getStatus() == StepStatus.FAILED)
                    .map(StepResult::getStepIndex)
                    .findFirst()
                    .orElse(null);
        }

        Map<String, Object> error = run.getError();
        String message = error != null && error.get("message") != null ? error.get("message").toString() : null;

        DeadLetterEntry entry = DeadLetterEntry.builder()
                .workflowRunId(run.getId())
                .workflowId(run.getWorkflow() != null ? run.getWorkflow().getId() : null)
                .workflowName(run.getWorkflowName())
                .errorMessage(message)
                .failedStepIndex(failedStepIndex)
                .retryCount(0)
                .lastFailureAt(Instant.now())
                .status(DeadLetterEntry.Status.OPEN)
                .build();

        DeadLetterEntry saved = repository.save(entry);
        log.info("Recorded DLQ entry {} for failed run {}", saved.getId(), run.getId());
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
