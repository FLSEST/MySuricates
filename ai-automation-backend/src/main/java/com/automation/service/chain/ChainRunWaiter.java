package com.automation.service.chain;

import com.automation.config.ChainConfig;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.automation.config.ExecutionDefaults.POLLING_INTERVAL_MS;

/**
 * Waits for a chain entry's workflow run to reach a terminal state (polling, with the
 * configured chain timeout and the chain's cancel flag) and reads its last step output.
 *
 * <p>Extracted from {@code ChainExecutor} (Audit-TODO 050b85d8).
 */
@Service
@RequiredArgsConstructor
public class ChainRunWaiter {

    private final ChainConfig chainConfig;
    private final WorkflowRunRepository workflowRunRepository;
    private final StepResultRepository stepResultRepository;

    public WorkflowRun waitForCompletion(String runId, AtomicBoolean cancelled) {
        return waitForCompletion(runId, cancelled, null);
    }

    /** As above, with its own upper limit for this chain entry (TODO cc0eb3ba); null = global default. */
    public WorkflowRun waitForCompletion(String runId, AtomicBoolean cancelled, Integer timeoutMinutes) {
        Duration maxWait = Duration.ofMinutes(timeoutMinutes != null ? timeoutMinutes : chainConfig.getWorkflowTimeoutMinutes());
        Instant deadline = Instant.now().plus(maxWait);
        while (Instant.now().isBefore(deadline)) {
            if (cancelled != null && cancelled.get()) {
                throw new java.util.concurrent.CancellationException("Chain execution cancelled");
            }
            WorkflowRun run = workflowRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING
                    && run.getStatus() != RunStatus.PENDING
                    && run.getStatus() != RunStatus.PAUSED) {
                return run;
            }
            try {
                Thread.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowExecutionException("Interrupted while waiting for workflow completion", e);
            }
        }
        throw new WorkflowExecutionException("Workflow timed out after " + maxWait.toMinutes() + " minutes");
    }

    public String extractOutput(WorkflowRun run) {
        // Use repository query instead of lazy-loaded collection to avoid
        // LazyInitializationException outside of a Hibernate session
        List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
        if (steps == null || steps.isEmpty()) {
            return null;
        }
        StepResult lastStep = steps.get(steps.size() - 1);
        return lastStep.getOutput();
    }
}
