package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ChainRun;
import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;
import com.automation.entity.WorkflowRun;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.scheduler.ScheduleConflictCheck.SkipReason;
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
import java.util.HashMap;
import java.util.Map;
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
    private final ModuleRegistry modules;
    private final ScheduleConflictCheck conflictCheck;

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

        // An occurrence at or after the end date no longer belongs to the schedule — stored before
        // advance() checked the next run against the end date, or the end date was moved forward by
        // an update. It ends the schedule instead of starting once more (TODO 9a3e6c91).
        if (scheduled.getScheduleType() == ScheduleType.CRON
                && reachesEndDate(scheduled, scheduled.getNextRunAt())) {
            endAtEndDate(scheduled);
            return Optional.empty();
        }

        // Conflict behaviour SKIP (default; OVERLAP allows parallel runs): while the target still has
        // a run in progress, this occurrence is dropped and the schedule moves on to its next one.
        // Returning without that left nextRunAt in the past, so every tick retried and the occurrence
        // started late, as soon as the other run ended (TODO ade3fef8).
        SkipReason skip = conflictCheck.reasonToSkip(scheduled);
        if (skip != null) {
            skipOccurrence(scheduled, skip);
            return Optional.empty();
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
            if (scheduled.getTargetType() == TargetType.CHAIN && !modules.isEnabled(ModuleCatalog.CHAINS)) {
                // Schedules created while chains were enabled; skip (the finally still advances nextRunAt)
                log.warn("Schedule {} skipped — {}", scheduleId,
                        ModuleCatalog.CHAINS.disabledMessage("Chain schedule '" + scheduled.getTargetName() + "'"));
                return Optional.empty();
            }
            if (scheduled.getTargetType() == TargetType.CHAIN && scheduled.getChain() != null) {
                ChainRun run = chainExecutor.execute(scheduled.getChain(), scheduled.getInputContext(), scheduleId);
                return Optional.ofNullable(run).map(r -> new ScheduledRunRef(r.getId(), TargetType.CHAIN));
            } else if (scheduled.getWorkflow() != null) {
                WorkflowRun run = workflowExecutor.execute(scheduled.getWorkflow(), scheduled.getInputContext(), scheduleId, null,
                        TriggerSource.SCHEDULE);
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

    /**
     * Drops the due occurrence without starting a run: the schedule moves on exactly as after a run
     * (CRON to its next occurrence, ONCE/DELAYED are done), but {@code lastRunAt} and
     * {@code runCount} stay — nothing ran, so the skip does not use up {@code maxRuns}.
     */
    private void skipOccurrence(ScheduledWorkflow scheduled, SkipReason reason) {
        Instant skippedAt = scheduled.getNextRunAt();
        ScheduledWorkflow advanced = advance(scheduled.getId(), scheduled.getScheduleType(),
                scheduled.getCronExpression(), false);
        Instant nextRunAt = advanced != null ? advanced.getNextRunAt() : null;
        log.warn("Schedule {} skipped — {} '{}': {}; next run at {}",
                scheduled.getId(), scheduled.getTargetType(), scheduled.getTargetName(), reason.text, nextRunAt);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", reason.key);
        if (skippedAt != null) {
            metadata.put("previousNextRunAt", skippedAt.toString());
        }
        if (nextRunAt != null) {
            metadata.put("nextRunAt", nextRunAt.toString());
        }
        activityLogService.log(LogLevel.INFO, LogCategory.SCHEDULE,
                "Schedule skipped (" + reason.text + "): " + scheduled.getTargetName(),
                "ScheduledWorkflowExecutor", scheduled.getId(), metadata);
    }

    /**
     * Ends a CRON schedule whose due occurrence lies at or after its end date, without starting it:
     * disabled as after its last run, while {@code lastRunAt} and {@code runCount} stay.
     */
    private void endAtEndDate(ScheduledWorkflow scheduled) {
        Instant occurrence = scheduled.getNextRunAt();
        advance(scheduled.getId(), scheduled.getScheduleType(), scheduled.getCronExpression(), false);
        log.info("Schedule {} ended — its occurrence at {} is not before its end date {}",
                scheduled.getId(), occurrence, scheduled.getEndDate());

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", "end-date");
        metadata.put("previousNextRunAt", occurrence.toString());
        metadata.put("endDate", scheduled.getEndDate().toString());
        activityLogService.log(LogLevel.INFO, LogCategory.SCHEDULE,
                "Schedule ended (end date reached): " + scheduled.getTargetName(),
                "ScheduledWorkflowExecutor", scheduled.getId(), metadata);
    }

    /**
     * True when the end date lies at or before {@code occurrence}: the schedule is over by then
     * ("disabled when now >= endDate"), so the occurrence does not run. Measured on the occurrence,
     * not on the moment the tick picks it up, so the last run before the end date does not depend
     * on where the 60-s tick falls.
     */
    private static boolean reachesEndDate(ScheduledWorkflow scheduled, Instant occurrence) {
        return scheduled.getEndDate() != null && occurrence != null
                && !occurrence.isBefore(scheduled.getEndDate());
    }

    @Transactional
    public void updateScheduleAfterRun(String scheduleId, ScheduleType scheduleType,
                                        String cronExpression) {
        advance(scheduleId, scheduleType, cronExpression, true);
    }

    /**
     * Moves a schedule past its due occurrence: ONCE/DELAYED are done and disabled, CRON goes to its
     * next occurrence unless an end condition is met. Only when {@code ran} (a start was attempted,
     * successful or not) are {@code lastRunAt} and {@code runCount} updated.
     *
     * @return the updated schedule, or {@code null} when it no longer exists
     */
    private ScheduledWorkflow advance(String scheduleId, ScheduleType scheduleType, String cronExpression,
                                      boolean ran) {
        ScheduledWorkflow scheduled = scheduledRepository.findById(scheduleId).orElse(null);
        if (scheduled == null) return null;

        if (ran) {
            scheduled.setLastRunAt(Instant.now(clock));
        }

        if (scheduleType == ScheduleType.ONCE || scheduleType == ScheduleType.DELAYED) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("{} schedule {} {} and disabled", scheduleType, scheduleId, ran ? "executed" : "skipped");
        } else {
            // Count the run and check the end conditions of a CRON schedule
            if (ran) {
                scheduled.setRunCount(scheduled.getRunCount() + 1);
            }

            Instant now = Instant.now(clock);
            boolean maxRunsReached = scheduled.getMaxRuns() > 0
                    && scheduled.getRunCount() >= scheduled.getMaxRuns();
            // The end date bounds the next run, not only this moment: a next run at or after it is no
            // longer part of the schedule. Setting it anyway started it once after the end date
            // (TODO 9a3e6c91). Without a next run to check (none left, or not computed), now counts.
            Instant next = maxRunsReached || reachesEndDate(scheduled, now) ? null : calculateNextRun(cronExpression);
            boolean endDateReached = reachesEndDate(scheduled, next != null ? next : now);

            if (maxRunsReached || endDateReached) {
                scheduled.setNextRunAt(null);
                scheduled.setEnabled(false);
                log.info("Schedule {} disabled — end condition met (maxRuns={}, runCount={}, endDate={})",
                        scheduleId, scheduled.getMaxRuns(), scheduled.getRunCount(), scheduled.getEndDate());
            } else {
                scheduled.setNextRunAt(next);
                log.info("Schedule {} {}, next run at: {}",
                        scheduleId, ran ? "executed" : "skipped", scheduled.getNextRunAt());
            }
        }

        scheduledRepository.save(scheduled);
        return scheduled;
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
