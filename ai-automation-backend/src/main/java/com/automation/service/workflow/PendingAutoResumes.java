package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which workflows have an automatic resume ahead or under way (TODO 070ccc3d). The scheduler asks
 * {@link #forWorkflow(String)} and skips a due occurrence then: after a restart or a standby the
 * interrupted run is INTERRUPTED, not RUNNING, while {@link RunAutoResumer} waits its delay — a
 * catch-up run started in that window ran next to the continuation. The continuation wins.
 *
 * <p>Read from the saved state rather than from the resumer's queue: a run counts from the moment
 * it is marked INTERRUPTED, before the event that queues it has arrived. What the resumer is done
 * with ({@link #handled}) no longer counts, so a run it passed over (not eligible, switched off,
 * refused) never blocks a schedule. The run of the attempt under way counts until the attempt has
 * ended — by then a resumed run is RUNNING and the scheduler's busy check sees it.
 */
@Component
public class PendingAutoResumes {

    private final WorkflowRunRepository runRepository;
    private final WorkflowRepository workflowRepository;

    /** Runs interrupted before this bean existed belong to an earlier lifetime and are never resumed. */
    private final Instant lifetimeStart = Instant.now();
    /** Interrupted runs the resumer is done with: attempted, found not eligible, or dropped. */
    private final Set<String> handled = ConcurrentHashMap.newKeySet();
    /** The run whose attempt is under way — its attempt is counted, it may not be RUNNING yet. */
    private volatile String inFlightRunId;

    public PendingAutoResumes(WorkflowRunRepository runRepository, WorkflowRepository workflowRepository) {
        this.runRepository = runRepository;
        this.workflowRepository = workflowRepository;
    }

    /**
     * Whether an automatic resume of this workflow is ahead or under way: a run of it interrupted in
     * this lifetime qualifies ({@link RunAutoResumer#skipReason}, opt-in) and is not handled yet, or
     * its attempt is running right now. Ask this <em>before</em> looking for a RUNNING/PAUSED run: a
     * resumed run is RUNNING before its attempt ends, so in that order no moment falls between.
     */
    public boolean forWorkflow(String workflowId) {
        List<WorkflowRun> interrupted = runRepository.findByWorkflowIdAndStatusAndCompletedAtGreaterThanEqual(
                workflowId, RunStatus.INTERRUPTED, lifetimeStart);
        if (interrupted.isEmpty()) {
            return false;
        }
        String inFlight = inFlightRunId;   // read after the runs: an attempt that ends in between is RUNNING
        if (interrupted.stream().anyMatch(run -> run.getId().equals(inFlight))) {
            return true;
        }
        boolean waiting = interrupted.stream()
                .anyMatch(run -> !handled.contains(run.getId()) && RunAutoResumer.skipReason(run) == null);
        return waiting && workflowRepository.findById(workflowId)
                .map(workflow -> RunAutoResumer.optedIn(workflow.getMetadata())).orElse(false);
    }

    /** Announced (again): the resumer decides anew, e.g. for a run a person resumed and that was interrupted again. */
    void reopen(Collection<String> runIds) {
        handled.removeAll(runIds);
    }

    /** The resumer will not look at these runs (switched off, or it could not schedule them). */
    void dropped(Collection<String> runIds) {
        handled.addAll(runIds);
    }

    void attemptStarted(String runId) {
        inFlightRunId = runId;
    }

    void attemptEnded(String runId) {
        handled.add(runId);
        inFlightRunId = null;
    }
}
