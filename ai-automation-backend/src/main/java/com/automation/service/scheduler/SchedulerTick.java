package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The 60s scheduler tick: fires due schedules, honours the skip-until window and the maintenance
 * mode, and applies one policy to occurrences the backend missed — whether the JVM was frozen
 * (laptop suspend, detected as a long gap between ticks) or the process was not running at all
 * (cold start, detected on the first tick). Missed occurrences are either advanced to their next
 * future time or, for schedules opted in via {@code catchUpOnResume} (or the global setting),
 * handed to the {@link CatchUpQueue}, which runs them one after another instead of all at once.
 * Schedule CRUD lives in {@link SchedulerService}.
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
    private final CatchUpQueue catchUpQueue;
    private final Clock clock;

    /** Wall-clock time of the previous tick; a large gap signals a system resume. */
    private Instant lastTickAt = null;

    /** When this backend instance came up; occurrences due before it were missed while stopped. */
    private volatile Instant startedAt = null;

    @PostConstruct
    void markStarted() {
        startedAt = Instant.now(clock);
    }

    @Scheduled(fixedRate = 60_000)
    public void checkAndExecuteScheduled() {
        Instant now = Instant.now(clock);
        Instant prevTick = lastTickAt;
        lastTickAt = now;
        if (startedAt == null) {
            startedAt = now;
        }

        if (maintenanceMode.isEnabled()) {
            log.debug("Scheduler in maintenance mode — skipping execution");
            return;
        }

        if (isResumeAfterSuspend(prevTick, now)) {
            handleResume(prevTick, now);
        } else if (isColdStart(prevTick)) {
            handleColdStart(now);
        } else {
            runDueSchedules(now);
        }

        catchUpQueue.drain(scheduledExecutor, now);
    }

    private void runDueSchedules(Instant now) {
        fireOrSkipUntil(dueSchedulesNotQueued(now), now, activeSkipUntil(now));
    }

    /** The manual skip-until window, if it is still ahead of {@code now}. */
    private Optional<Instant> activeSkipUntil(Instant now) {
        return skipPolicy.parseSkipUntil().filter(now::isBefore);
    }

    private void fireOrSkipUntil(List<ScheduledWorkflow> due, Instant now, Optional<Instant> skipUntil) {
        if (skipUntil.isPresent()) {
            due.forEach(scheduled -> skipPolicy.skipScheduleUntil(scheduled, skipUntil.get()));
            return;
        }
        due.forEach(scheduled -> execute(scheduled,
                "Failed to execute scheduled workflow: {}", "Schedule execution failed: "));
    }

    /** A tick that fired far later than the fixed 60s rate means the JVM was frozen (e.g. suspend). */
    private boolean isResumeAfterSuspend(Instant prevTick, Instant now) {
        long graceMinutes = schedulerConfig.getResumeGraceMinutes();
        return prevTick != null && graceMinutes > 0
                && Duration.between(prevTick, now).compareTo(Duration.ofMinutes(graceMinutes)) > 0;
    }

    /** The first tick of this JVM: occurrences due before the start were missed while stopped. */
    private boolean isColdStart(Instant prevTick) {
        return prevTick == null && schedulerConfig.getResumeGraceMinutes() > 0;
    }

    private void handleResume(Instant prevTick, Instant now) {
        long gapMinutes = Duration.between(prevTick, now).toMinutes();
        suspendState.recordResume(now, Duration.between(prevTick, now).toMillis());

        // Everything due now became due while the JVM was frozen.
        MissedSummary summary = applyMissedPolicy(dueSchedulesNotQueued(now), now, "suspend-resume",
                scheduled -> skipPolicy.skipScheduleForResume(scheduled, now, gapMinutes));
        String message = "System resume detected (paused ~" + gapMinutes + " min) — "
                + summary.describe();
        log.warn(message);
        activityLogService.warn(LogCategory.SCHEDULE, message, "SchedulerService");
    }

    private void handleColdStart(Instant now) {
        Instant reference = startedAt;
        List<ScheduledWorkflow> missed = new ArrayList<>();
        List<ScheduledWorkflow> regular = new ArrayList<>();
        for (ScheduledWorkflow scheduled : dueSchedulesNotQueued(now)) {
            if (scheduled.getNextRunAt() != null && scheduled.getNextRunAt().isBefore(reference)) {
                missed.add(scheduled);
            } else {
                regular.add(scheduled);
            }
        }
        if (!missed.isEmpty()) {
            MissedSummary summary = applyMissedPolicy(missed, now, "cold-start",
                    scheduled -> skipPolicy.skipScheduleForColdStart(scheduled, now, reference));
            String message = "Cold start detected (backend was not running) — " + summary.describe();
            log.warn(message);
            activityLogService.warn(LogCategory.SCHEDULE, message, "SchedulerService");
        }
        fireOrSkipUntil(regular, now, activeSkipUntil(now));
    }

    /**
     * One rule for both cases: opted-in schedules go to the sequential catch-up queue, all others
     * are advanced past the missed occurrence without firing. During a manual skip-until pause
     * nothing is caught up — the pause wins.
     */
    private MissedSummary applyMissedPolicy(List<ScheduledWorkflow> missed, Instant now, String reason,
                                            java.util.function.Consumer<ScheduledWorkflow> skip) {
        Optional<Instant> skipUntil = activeSkipUntil(now);
        int queued = 0;
        int skipped = 0;
        for (ScheduledWorkflow scheduled : missed) {
            if (skipUntil.isPresent()) {
                skipPolicy.skipScheduleUntil(scheduled, skipUntil.get());
                skipped++;
            } else if (scheduled.isCatchUpOnResume() || schedulerConfig.isCatchUpOnResume()) {
                catchUpQueue.enqueue(scheduled, scheduled.getNextRunAt(), reason);
                queued++;
            } else {
                try {
                    skip.accept(scheduled);
                    skipped++;
                } catch (Exception e) {
                    log.error("Failed to skip schedule {}: {}", scheduled.getId(), e.getMessage(), e);
                    activityLogService.error(LogCategory.SCHEDULE, "Schedule skip failed: " + e.getMessage(),
                            "SchedulerService", scheduled.getId());
                }
            }
        }
        return new MissedSummary(missed.size(), skipped, queued);
    }

    private record MissedSummary(int missed, int skipped, int queued) {
        String describe() {
            return missed + " missed schedule(s): " + skipped + " skipped forward, "
                    + queued + " queued for sequential catch-up";
        }
    }

    /** Due schedules minus the ones waiting in (or running from) the catch-up queue. */
    private List<ScheduledWorkflow> dueSchedulesNotQueued(Instant now) {
        List<ScheduledWorkflow> due = scheduledRepository.findDueSchedules(now);
        if (catchUpQueue.isEmpty()) return due;
        return due.stream().filter(s -> !catchUpQueue.contains(s.getId())).toList();
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
