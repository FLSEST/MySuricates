package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.repository.WorkflowRunRepository;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

/**
 * Polls a run started by another run until it leaves {@link #IN_MOTION} — the one wait loop behind the
 * {@code sub-workflow} block, {@code foreach} with {@code workflow_id} and chain entries (TODO 802a516e).
 * Before, each kept its own copy and the sub-workflow one ended on a PAUSED child.
 *
 * <p>The end is "no longer in motion" rather than {@link RunCleanupService#TERMINAL_STATUSES}: an
 * INTERRUPTED run (standby, restart) is not a retention candidate, but nothing advances it on its own,
 * so waiting for it would only run into the timeout — both callers ended on it before and still do.
 */
public final class RunCompletionWaiter {

    /** Statuses of a run that may still move on by itself — the wait goes on while the run is in one. */
    public static final Set<RunStatus> IN_MOTION = EnumSet.of(RunStatus.PENDING, RunStatus.RUNNING, RunStatus.PAUSED);

    /** What the caller does around the polling loop; both hooks are optional. */
    public interface Listener {
        /** Runs before every poll. May throw to abort the wait — the exception reaches the caller unchanged. */
        default void beforePoll() {
        }

        /** Runs once when the wait gives up on a run still in motion (timeout or interrupt), before it throws. */
        default void onGiveUp() {
        }
    }

    private RunCompletionWaiter() {
    }

    /**
     * Waits for run {@code runId} to leave {@link #IN_MOTION} and returns it as last read.
     *
     * @param label names the run in errors: "{label} timed out after N minutes",
     *              "Interrupted while waiting for {label}", "{label} run … disappeared while waiting"
     * @throws WorkflowExecutionException on timeout, interruption (the flag stays set) or a vanished run
     */
    public static WorkflowRun await(WorkflowRunRepository runs, String runId, String label, Duration maxWait,
                                    Duration pollInterval, Listener listener) {
        Instant deadline = Instant.now().plus(maxWait);
        while (Instant.now().isBefore(deadline)) {
            listener.beforePoll();
            WorkflowRun run = runs.findById(runId).orElseThrow(() ->
                    new WorkflowExecutionException(label + " run " + runId + " disappeared while waiting"));
            if (!IN_MOTION.contains(run.getStatus())) {
                return run;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                listener.onGiveUp();
                throw new WorkflowExecutionException(
                        "Interrupted while waiting for " + label.toLowerCase(Locale.ROOT), e);
            }
        }
        listener.onGiveUp();
        throw new WorkflowExecutionException(label + " timed out after " + maxWait.toMinutes() + " minutes");
    }
}
