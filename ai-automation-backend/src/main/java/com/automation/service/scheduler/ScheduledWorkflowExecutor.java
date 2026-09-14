package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.WorkflowRun;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledWorkflowExecutor {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final WorkflowExecutor workflowExecutor;
    private final ChainExecutor chainExecutor;
    private final ActivityLogService activityLogService;
    private final Clock clock;

    /**
     * Execute a scheduled workflow/chain.
     * <p>
     * Intentionally NOT @Transactional on the outer method: with SQLite pool-size=1, a
     * long-running @Transactional would hold the single HikariCP connection for the entire
     * workflow duration (potentially 30+ minutes), blocking all other DB operations including
     * read-only Executions API calls.
     * <p>
     * Schedule metadata reads/writes use their own short-lived repository transactions, and
     * workflow execution manages its own transaction scope internally.
     */
    public Optional<ScheduledRunRef> executeSingleSchedule(String scheduleId) {
        // Use JOIN FETCH query to eagerly load workflow.activatedSkills within the repository's
        // own transaction. Plain findById() leaves the JPA session closed, causing
        // LazyInitializationException when WorkflowExecutor.execute() accesses activatedSkills.
        ScheduledWorkflow scheduled = scheduledRepository.findByIdWithWorkflowSkills(scheduleId).orElse(null);

        if (scheduled == null || !scheduled.isEnabled()
                || scheduled.getNextRunAt() == null
                || scheduled.getNextRunAt().isAfter(Instant.now(clock))) {
            return Optional.empty();
        }

        // Ghost-run protection: skip if the target already has a RUNNING execution.
        // Only enforced when conflictBehavior == SKIP (OVERLAP allows parallel runs).
        if (scheduled.getConflictBehavior() != ScheduledWorkflow.ConflictBehavior.OVERLAP) {
            if (scheduled.getTargetType() == TargetType.WORKFLOW && scheduled.getWorkflow() != null) {
                if (workflowRunRepository.existsByWorkflowIdAndStatus(
                        scheduled.getWorkflow().getId(), RunStatus.RUNNING)) {
                    log.warn("Schedule {} skipped — workflow '{}' already has a RUNNING execution",
                            scheduleId, scheduled.getWorkflow().getName());
                    return Optional.empty();
                }
            }
            if (scheduled.getTargetType() == TargetType.CHAIN && scheduled.getChain() != null) {
                if (chainRunRepository.existsByChainIdAndStatus(
                        scheduled.getChain().getId(), RunStatus.RUNNING)) {
                    log.warn("Schedule {} skipped — chain '{}' already has a RUNNING execution",
                            scheduleId, scheduled.getChain().getName());
                    return Optional.empty();
                }
            }
        }

        log.info("Executing scheduled {}: {} (type: {})",
                scheduled.getTargetType(), scheduled.getId(), scheduled.getScheduleType());

        activityLogService.info(LogCategory.SCHEDULE,
                "Schedule triggered: " + scheduled.getTargetName(),
                "ScheduledWorkflowExecutor", scheduled.getId());

        // try-finally ensures updateScheduleAfterRun is ALWAYS called, even if execute() throws.
        // Without this, a failing execute() leaves nextRunAt in the past, causing an infinite
        // retry loop on the next @Scheduled tick.
        try {
            if (scheduled.getTargetType() == TargetType.CHAIN && scheduled.getChain() != null) {
                ChainRun run = chainExecutor.execute(scheduled.getChain(), scheduled.getInputContext(), scheduleId);
                return Optional.ofNullable(run).map(r -> new ScheduledRunRef(r.getId(), TargetType.CHAIN));
            } else if (scheduled.getWorkflow() != null) {
                WorkflowRun run = workflowExecutor.execute(scheduled.getWorkflow(), scheduled.getInputContext(), scheduleId, null);
                return Optional.ofNullable(run).map(r -> new ScheduledRunRef(r.getId(), TargetType.WORKFLOW));
            } else {
                log.error("Schedule {} has no valid target", scheduled.getId());
                return Optional.empty();
            }
        } finally {
            // Short write transaction for schedule metadata update
            updateScheduleAfterRun(scheduleId, scheduled.getScheduleType(),
                    scheduled.getCronExpression());
        }
    }

    /**
     * Handle on the run a schedule started, so the caller (the catch-up queue) can wait for it
     * to reach a terminal state before starting the next one. Execution itself is asynchronous.
     */
    public record ScheduledRunRef(String runId, TargetType targetType) {}

    /** True once the referenced run is no longer PENDING/RUNNING/PAUSED (or no longer exists). */
    public boolean isFinished(ScheduledRunRef ref) {
        if (ref == null || ref.runId() == null) return true;
        Optional<RunStatus> status = ref.targetType() == TargetType.CHAIN
                ? chainRunRepository.findById(ref.runId()).map(ChainRun::getStatus)
                : workflowRunRepository.findById(ref.runId()).map(WorkflowRun::getStatus);
        return status.map(s -> s != RunStatus.PENDING && s != RunStatus.RUNNING && s != RunStatus.PAUSED)
                .orElse(true);
    }

    @Transactional
    public void updateScheduleAfterRun(String scheduleId, ScheduleType scheduleType,
                                        String cronExpression) {
        ScheduledWorkflow scheduled = scheduledRepository.findById(scheduleId).orElse(null);
        if (scheduled == null) return;

        scheduled.setLastRunAt(Instant.now(clock));

        if (scheduleType == ScheduleType.ONCE || scheduleType == ScheduleType.DELAYED) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("{} schedule {} executed and disabled", scheduleType, scheduleId);
        } else {
            // Increment run counter and check end conditions for CRON schedules
            scheduled.setRunCount(scheduled.getRunCount() + 1);

            boolean maxRunsReached = scheduled.getMaxRuns() > 0
                    && scheduled.getRunCount() >= scheduled.getMaxRuns();
            boolean endDateReached = scheduled.getEndDate() != null
                    && Instant.now(clock).isAfter(scheduled.getEndDate());

            if (maxRunsReached || endDateReached) {
                scheduled.setNextRunAt(null);
                scheduled.setEnabled(false);
                log.info("Schedule {} disabled — end condition met (maxRuns={}, runCount={}, endDate={})",
                        scheduleId, scheduled.getMaxRuns(), scheduled.getRunCount(), scheduled.getEndDate());
            } else {
                scheduled.setNextRunAt(calculateNextRun(cronExpression));
                log.info("Schedule {} executed, next run at: {}",
                        scheduleId, scheduled.getNextRunAt());
            }
        }

        scheduledRepository.save(scheduled);
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
