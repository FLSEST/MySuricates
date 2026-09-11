package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * The 60s scheduler tick: fires due schedules, honours the skip-until window and the maintenance
 * mode, and turns a long gap between ticks (laptop suspend) into a per-schedule catch-up decision
 * instead of a stampede. Schedule CRUD lives in {@link SchedulerService}.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchedulerTick {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final ScheduledWorkflowExecutor scheduledExecutor;
    private final ActivityLogService activityLogService;
    private final SchedulerConfig schedulerConfig;
    private final SchedulerMaintenanceMode maintenanceMode;
    private final SuspendState suspendState;
    private final ScheduleSkipPolicy skipPolicy;
    private final Clock clock;

    /** Wall-clock time of the previous tick; a large gap signals a system resume. */
    private Instant lastTickAt = null;

    @Scheduled(fixedRate = 60_000)
    public void checkAndExecuteScheduled() {
        Instant now = Instant.now(clock);
        Instant prevTick = lastTickAt;
        lastTickAt = now;

        if (maintenanceMode.isEnabled()) {
            log.debug("Scheduler in maintenance mode — skipping execution");
            return;
        }
        if (isResumeAfterSuspend(prevTick, now)) {
            handleResume(prevTick, now);
            return;
        }

        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(now);
        Optional<Instant> skipUntil = skipPolicy.parseSkipUntil();
        if (skipUntil.isPresent() && now.isBefore(skipUntil.get())) {
            dueSchedules.forEach(scheduled -> skipPolicy.skipScheduleUntil(scheduled, skipUntil.get()));
            return;
        }
        dueSchedules.forEach(scheduled -> execute(scheduled,
                "Failed to execute scheduled workflow: {}", "Schedule execution failed: "));
    }

    /** A tick that fired far later than the fixed 60s rate means the JVM was frozen (e.g. suspend). */
    private boolean isResumeAfterSuspend(Instant prevTick, Instant now) {
        long graceMinutes = schedulerConfig.getResumeGraceMinutes();
        return prevTick != null && graceMinutes > 0
                && Duration.between(prevTick, now).compareTo(Duration.ofMinutes(graceMinutes)) > 0;
    }

    /**
     * Schedules with {@code catchUpOnResume=true} run once; all others are skipped forward to their
     * next future occurrence, so a weekend of missed daily radars does not fire all at once.
     */
    private void handleResume(Instant prevTick, Instant now) {
        long gapMinutes = Duration.between(prevTick, now).toMinutes();
        suspendState.recordResume(now, Duration.between(prevTick, now).toMillis());

        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(now);
        String message = "System resume detected (paused ~" + gapMinutes + " min) — applying catch-up policy to "
                + dueSchedules.size() + " due schedule(s)";
        log.warn(message);
        activityLogService.warn(LogCategory.SCHEDULE, message, "SchedulerService");

        for (ScheduledWorkflow scheduled : dueSchedules) {
            if (scheduled.isCatchUpOnResume() || schedulerConfig.isCatchUpOnResume()) {
                execute(scheduled, "Failed to catch up scheduled workflow on resume: {}", "Schedule catch-up failed: ");
            } else {
                skipPolicy.skipScheduleForResume(scheduled, now, gapMinutes);
            }
        }
    }

    private void execute(ScheduledWorkflow scheduled, String logMessage, String activityPrefix) {
        try {
            scheduledExecutor.executeSingleSchedule(scheduled.getId());
        } catch (Exception e) {
            log.error(logMessage, scheduled.getId(), e);
            activityLogService.error(LogCategory.SCHEDULE, activityPrefix + e.getMessage(),
                    "SchedulerService", scheduled.getId());
        }
    }
}
