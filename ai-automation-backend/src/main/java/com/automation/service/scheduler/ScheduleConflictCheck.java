package com.automation.service.scheduler;

import com.automation.entity.RunStatus;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.PendingAutoResumes;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

/**
 * Conflict behaviour SKIP (the default): whether a due occurrence is dropped because its target is
 * still busy, and why. {@link ScheduledWorkflowExecutor} records the reason in the activity log.
 */
@Component
@RequiredArgsConstructor
class ScheduleConflictCheck {

    /**
     * Run states in which the target is still busy for a SKIP schedule. PAUSED counts: a paused run
     * continues later, and a second run next to it is exactly the overlap SKIP avoids. PENDING does
     * not: both executors create their runs as RUNNING, and the startup cleanup only resolves
     * RUNNING/PAUSED leftovers — a stale PENDING row would block the schedule for good.
     */
    static final Set<RunStatus> BUSY_STATES = EnumSet.of(RunStatus.RUNNING, RunStatus.PAUSED);

    /** Why a SKIP schedule drops its due occurrence: the activity-log reason and its wording. */
    enum SkipReason {
        TARGET_BUSY("target-busy", "previous run still in progress"),
        AUTO_RESUME_PENDING("auto-resume-pending", "interrupted run is being resumed automatically");

        final String key;
        final String text;

        SkipReason(String key, String text) {
            this.key = key;
            this.text = text;
        }
    }

    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final PendingAutoResumes pendingAutoResumes;

    /**
     * Why the occurrence is skipped, or {@code null} when it may start. Never for OVERLAP, which
     * allows parallel runs. Besides a RUNNING or PAUSED run of the target, a workflow's run that the
     * auto-resumer is about to continue counts (TODO 070ccc3d): the restart or standby marked it
     * INTERRUPTED, so the status check alone missed it while the resumer waited its delay, and a
     * catch-up run started in that window ran next to the continuation. The pending resume is asked
     * first — it becomes a RUNNING run, so in this order the two reads leave no gap.
     */
    SkipReason reasonToSkip(ScheduledWorkflow scheduled) {
        if (scheduled.getConflictBehavior() == ScheduledWorkflow.ConflictBehavior.OVERLAP) {
            return null;
        }
        if (scheduled.getTargetType() != TargetType.CHAIN && scheduled.getWorkflow() != null
                && pendingAutoResumes.forWorkflow(scheduled.getWorkflow().getId())) {
            return SkipReason.AUTO_RESUME_PENDING;
        }
        return targetBusy(scheduled) ? SkipReason.TARGET_BUSY : null;
    }

    /** True while the target still has a RUNNING or PAUSED run ({@link #BUSY_STATES}). */
    private boolean targetBusy(ScheduledWorkflow scheduled) {
        if (scheduled.getTargetType() == TargetType.CHAIN) {
            return scheduled.getChain() != null
                    && chainRunRepository.existsByChainIdAndStatusIn(scheduled.getChain().getId(), BUSY_STATES);
        }
        return scheduled.getWorkflow() != null
                && workflowRunRepository.existsByWorkflowIdAndStatusIn(scheduled.getWorkflow().getId(), BUSY_STATES);
    }
}
