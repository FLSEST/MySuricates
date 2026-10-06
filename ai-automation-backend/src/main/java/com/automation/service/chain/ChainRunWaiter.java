package com.automation.service.chain;

import com.automation.config.ChainConfig;
import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunCompletionWaiter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.automation.config.ExecutionDefaults.POLLING_INTERVAL_MS;

/**
 * Waits for a chain entry's workflow run to end ({@link RunCompletionWaiter}, with the configured
 * chain timeout and the chain's cancel flag) and reads its last step output.
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

    /**
     * As above, with its own upper limit for this chain entry (TODO cc0eb3ba); null = global default.
     * A raised cancel flag ends the wait with a {@link CancellationException}; a run still in motion at
     * the timeout is left running, as before.
     */
    public WorkflowRun waitForCompletion(String runId, AtomicBoolean cancelled, Integer timeoutMinutes) {
        Duration maxWait = Duration.ofMinutes(timeoutMinutes != null ? timeoutMinutes : chainConfig.getWorkflowTimeoutMinutes());
        return RunCompletionWaiter.await(workflowRunRepository, runId, "Workflow", maxWait,
                Duration.ofMillis(POLLING_INTERVAL_MS), new RunCompletionWaiter.Listener() {
                    @Override
                    public void beforePoll() {
                        if (cancelled != null && cancelled.get()) {
                            throw new CancellationException("Chain execution cancelled");
                        }
                    }
                });
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
