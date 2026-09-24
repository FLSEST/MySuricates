package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Skip policy of the scheduler tick: the configured manual skip-until window (fail-open on a
 * bad value, warned once per distinct value) and advancing a due schedule past a window without
 * firing it — for the skip-until window and for a detected system suspend/resume. The read views
 * use the same rules to show the next run the scheduler will actually start (TODO a776fd2a), and
 * the same place to honour the maintenance switch, under which nothing starts (TODO 7704b1e9).
 *
 * <p>Extracted from {@code SchedulerService} (Audit-TODO 05dbd0e9). Resume detection and the
 * catch-up decision stay in the service; this class only moves schedules and logs the skip.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduleSkipPolicy {

    private final SchedulerConfig schedulerConfig;
    private final ScheduledWorkflowRepository scheduledRepository;
    private final ActivityLogService activityLogService;
    private final SchedulerMaintenanceMode maintenanceMode;

    /** Last unparseable skip-until value we already warned about, to avoid logging every 60s tick. */
    private volatile String lastInvalidSkipUntil = null;

    public Optional<Instant> parseSkipUntil() {
        String raw = schedulerConfig.getSkipUntil();
        if (raw == null || raw.isBlank()) {
            lastInvalidSkipUntil = null;
            return Optional.empty();
        }

        try {
            Optional<Instant> parsed = Optional.of(Instant.parse(raw.trim()));
            lastInvalidSkipUntil = null;
            return parsed;
        } catch (DateTimeParseException e) {
            // Fail open: a misconfigured (unparseable) skip-until must NOT halt the whole scheduler.
            // Treat it as "no skip" so schedules keep running, and warn once per distinct bad value
            // (this runs every 60s — logging every tick would spam the log and the ActivityLog/DB).
            if (!raw.equals(lastInvalidSkipUntil)) {
                lastInvalidSkipUntil = raw;
                log.error("Invalid automation.scheduler.skip-until value '{}'; ignoring it and running schedules normally", raw);
                activityLogService.warn(LogCategory.SCHEDULE,
                        "Invalid scheduler skip-until value (ignored): " + raw,
                        "SchedulerService");
            }
            return Optional.empty();
        }
    }

    /**
     * End of the skip-until window while it is still ahead of {@code now}, for the read views.
     * Parsed without side effects: the tick warns about an unparseable value, a GET must not write
     * activity-log rows from inside a read-only transaction.
     */
    public Optional<Instant> activeSkipUntil(Instant now) {
        String raw = schedulerConfig.getSkipUntil();
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Instant.parse(raw.trim())).filter(now::isBefore);
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /**
     * Where a schedule lands when it is skipped past {@code until}: a CRON schedule at its first
     * occurrence at or after {@code until}, a ONCE/DELAYED schedule nowhere ({@code null}) — its
     * single firing is dropped.
     */
    public Instant nextRunAfter(ScheduledWorkflow scheduled, Instant until) {
        if (scheduled.getScheduleType() != ScheduleType.CRON) {
            return null;
        }
        CronExpression cron = CronExpression.parse(scheduled.getCronExpression());
        LocalDateTime next = cron.next(LocalDateTime.ofInstant(until, ZoneId.systemDefault()).minusNanos(1));
        return next != null ? next.atZone(ZoneId.systemDefault()).toInstant() : null;
    }

    /**
     * Both pauses at {@code now}, for the read views: the maintenance switch and an active skip-until
     * window. One read per view, then handed along — not one per schedule.
     */
    public SchedulerPause activePause(Instant now) {
        return new SchedulerPause(maintenanceMode.isEnabled(), activeSkipUntil(now));
    }

    /** True when the stored next run lies inside the window: the tick will skip it, not start it. */
    public boolean heldBySkipUntil(ScheduledWorkflow scheduled, Optional<Instant> skipUntil) {
        return skipUntil.isPresent() && scheduled.getNextRunAt() != null
                && scheduled.getNextRunAt().isBefore(skipUntil.get());
    }

    /**
     * True when the maintenance switch holds the schedule: it has a next run, but the tick starts
     * nothing while the switch is on — and when it goes off, nobody knows (TODO 7704b1e9).
     */
    public boolean heldByMaintenance(ScheduledWorkflow scheduled, SchedulerPause pause) {
        return pause.maintenance() && scheduled.getNextRunAt() != null;
    }

    /**
     * The next run the scheduler will actually start: none while the maintenance switch holds the
     * schedule, else the run behind an active skip-until window ({@link #effectiveNextRun(
     * ScheduledWorkflow, Optional)}). The one rule of every read view — list, calendar, workflow
     * page, assistant.
     */
    public Instant effectiveNextRun(ScheduledWorkflow scheduled, SchedulerPause pause) {
        if (heldByMaintenance(scheduled, pause)) {
            return null;
        }
        return effectiveNextRun(scheduled, pause.skipUntil());
    }

    /**
     * The skip-until part of {@link #effectiveNextRun(ScheduledWorkflow, SchedulerPause)}. The tick
     * advances a schedule past the skip-until window only in the tick in which it becomes due; until
     * then the stored {@code nextRunAt} may lie inside the window — a run that never happens. For
     * such a schedule this is {@link #nextRunAfter} (null for a one-off, which expires); otherwise
     * the stored value. A cron expression that no longer parses keeps the stored value instead of
     * failing the view.
     */
    Instant effectiveNextRun(ScheduledWorkflow scheduled, Optional<Instant> skipUntil) {
        if (!heldBySkipUntil(scheduled, skipUntil)) {
            return scheduled.getNextRunAt();
        }
        try {
            return nextRunAfter(scheduled, skipUntil.get());
        } catch (IllegalArgumentException e) {
            return scheduled.getNextRunAt();
        }
    }

    /** Skip a schedule forward past a manual skip-until maintenance window. */
    public void skipScheduleUntil(ScheduledWorkflow scheduled, Instant until) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("skipUntil", until.toString());
        skipSchedule(scheduled, until, "scheduler-skip-until",
                "Schedule skipped: " + scheduled.getTargetName(), extra);
    }

    /** Skip a schedule forward because the system resumed after a long suspend/freeze. */
    public void skipScheduleForResume(ScheduledWorkflow scheduled, Instant now, long gapMinutes) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("suspendGapMinutes", gapMinutes);
        skipSchedule(scheduled, now, "suspend-resume",
                "Schedule skipped (system suspend/resume): " + scheduled.getTargetName(), extra);
    }

    /** Skip a schedule forward because its occurrence fell into a period the backend was stopped. */
    public void skipScheduleForColdStart(ScheduledWorkflow scheduled, Instant now, Instant startedAt) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("backendStartedAt", startedAt.toString());
        skipSchedule(scheduled, now, "cold-start",
                "Schedule skipped (backend was stopped): " + scheduled.getTargetName(), extra);
    }

    /**
     * Advance a schedule past {@code until} without firing it, and record an ActivityLog entry.
     * CRON schedules move to their next occurrence after {@code until}; ONCE/DELAYED schedules that
     * fall inside the window are permanently disabled (their single firing is dropped).
     */
    private void skipSchedule(ScheduledWorkflow scheduled, Instant until, String reason,
                              String message, Map<String, Object> extraMetadata) {
        Instant previousNextRunAt = scheduled.getNextRunAt();

        if (scheduled.getScheduleType() == ScheduleType.CRON) {
            scheduled.setNextRunAt(nextRunAfter(scheduled, until));
        } else if (scheduled.getScheduleType() == ScheduleType.ONCE
                || scheduled.getScheduleType() == ScheduleType.DELAYED) {
            // Intentional: a one-off schedule that falls inside the skip window is permanently
            // disabled (not deferred). Its single firing is dropped — unlike CRON, which resumes
            // at the next occurrence after the window.
            scheduled.setEnabled(false);
            scheduled.setNextRunAt(null);
        }

        scheduledRepository.save(scheduled);
        log.info("Schedule {} skipped ({}) past {}", scheduled.getId(), reason, until);

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("reason", reason);
        if (previousNextRunAt != null) {
            metadata.put("previousNextRunAt", previousNextRunAt.toString());
        }
        if (scheduled.getNextRunAt() != null) {
            metadata.put("nextRunAt", scheduled.getNextRunAt().toString());
        }
        if (extraMetadata != null) {
            metadata.putAll(extraMetadata);
        }

        activityLogService.log(
                LogLevel.INFO,
                LogCategory.SCHEDULE,
                message,
                "SchedulerService",
                scheduled.getId(),
                metadata
        );
    }
}
