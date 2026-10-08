package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;

/**
 * Moves a schedule past its due occurrence, after a run of {@link ScheduledWorkflowExecutor} or when
 * that occurrence is dropped. Split from the executor with TODO 0d2704db, which made the advance a
 * transaction of its own.
 */
@Component
@RequiredArgsConstructor
@Slf4j
class ScheduleAdvancer {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final ActivityLogService activityLogService;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /** What {@link #advance} changed; read after its transaction. */
    private record Advanced(ScheduledWorkflow schedule, boolean noFurtherOccurrence) {}

    /**
     * Moves a schedule past its due occurrence: ONCE/DELAYED are done and disabled, CRON goes to its
     * next occurrence unless an end condition is met. Only when {@code ran} (a start was attempted,
     * successful or not) are {@code lastRunAt} and {@code runCount} updated.
     *
     * <p>One short transaction (TODO 0d2704db): the schedule is read inside it, so the type, the cron
     * expression and the end conditions are the stored ones, and only the fields of the advance are
     * written. Before, it was read and merged back in separate auto-commits with the cron expression
     * from before the start: an update saved in between (disabled, another cron) was overwritten. A
     * stored next run that is no longer {@code dueAt} was set by such an update while the run was
     * starting (disable, a new cron or date, re-enable); it stays, and only the run is recorded. The
     * activity-log entry is written after the transaction.
     *
     * @param dueAt the occurrence this call moves past, as read before the start
     * @return the updated schedule, or {@code null} when it no longer exists
     */
    ScheduledWorkflow advance(String scheduleId, Instant dueAt, boolean ran) {
        Advanced advanced = transactionTemplate.execute(status -> advanceInTransaction(scheduleId, dueAt, ran));
        if (advanced == null) return null;
        if (advanced.noFurtherOccurrence()) {
            activityLogService.log(LogLevel.INFO, LogCategory.SCHEDULE,
                    "Schedule ended (no further occurrence): " + advanced.schedule().getTargetName(),
                    "ScheduledWorkflowExecutor", scheduleId, Map.of("reason", "no-next-run"));
        }
        return advanced.schedule();
    }

    private Advanced advanceInTransaction(String scheduleId, Instant dueAt, boolean ran) {
        ScheduledWorkflow scheduled = scheduledRepository.findById(scheduleId).orElse(null);
        if (scheduled == null) return null;

        ScheduleType scheduleType = scheduled.getScheduleType();
        if (ran) {
            scheduled.setLastRunAt(Instant.now(clock));
            if (scheduleType == ScheduleType.CRON) {
                scheduled.setRunCount(scheduled.getRunCount() + 1);
            }
        }

        boolean noFurtherOccurrence = false;
        if (!Objects.equals(scheduled.getNextRunAt(), dueAt)) {
            keepNextRunSetMeanwhile(scheduled, dueAt, ran);
        } else if (scheduleType == ScheduleType.ONCE || scheduleType == ScheduleType.DELAYED) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("{} schedule {} {} and disabled", scheduleType, scheduleId, ran ? "executed" : "skipped");
        } else {
            noFurtherOccurrence = advanceCron(scheduled, ran);
        }

        scheduledRepository.save(scheduled);
        return new Advanced(scheduled, noFurtherOccurrence);
    }

    /**
     * The next run was decided while the run was starting – by the user, not by this occurrence. It
     * stays as it is; only a CRON schedule whose run used up {@code maxRuns} still ends here.
     */
    private void keepNextRunSetMeanwhile(ScheduledWorkflow scheduled, Instant dueAt, boolean ran) {
        if (scheduled.getScheduleType() == ScheduleType.CRON && ScheduleEndConditions.maxRunsReached(scheduled)) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("Schedule {} disabled — end condition met (maxRuns={}, runCount={})",
                    scheduled.getId(), scheduled.getMaxRuns(), scheduled.getRunCount());
            return;
        }
        log.info("Schedule {} {} — changed meanwhile (due {}, now next run at {}, enabled={}), keeping that",
                scheduled.getId(), ran ? "executed" : "skipped", dueAt, scheduled.getNextRunAt(), scheduled.isEnabled());
    }

    /**
     * Moves a CRON schedule to its next occurrence of the stored expression, or ends it.
     *
     * @return whether it ended because the expression has no further occurrence
     */
    private boolean advanceCron(ScheduledWorkflow scheduled, boolean ran) {
        String scheduleId = scheduled.getId();
        String cronExpression = scheduled.getCronExpression();
        Instant now = Instant.now(clock);
        boolean maxRunsReached = ScheduleEndConditions.maxRunsReached(scheduled);
        // The end date bounds the next run, not only this moment: a next run at or after it is no
        // longer part of the schedule. Setting it anyway started it once after the end date
        // (TODO 9a3e6c91). Without a next run to check (none left, or not computed), now counts.
        Instant next = maxRunsReached || ScheduleEndConditions.reachesEndDate(scheduled, now)
                ? null : calculateNextRun(cronExpression);
        boolean endDateReached = ScheduleEndConditions.reachesEndDate(scheduled, next != null ? next : now);

        if (maxRunsReached || endDateReached) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("Schedule {} disabled — end condition met (maxRuns={}, runCount={}, endDate={})",
                    scheduleId, scheduled.getMaxRuns(), scheduled.getRunCount(), scheduled.getEndDate());
            return false;
        }
        if (next == null) {
            // A valid expression without a future occurrence (30 February): ended like at the end
            // date and reported once, instead of enabled with no next run (TODO 1e801952).
            scheduled.setEnabled(false);
            scheduled.setNextRunAt(null);
            log.info("Schedule {} disabled — cron expression '{}' has no further occurrence", scheduleId, cronExpression);
            return true;
        }
        scheduled.setNextRunAt(next);
        log.info("Schedule {} {}, next run at: {}", scheduleId, ran ? "executed" : "skipped", next);
        return false;
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
