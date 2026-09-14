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
 * firing it — for the skip-until window and for a detected system suspend/resume.
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
            CronExpression cron = CronExpression.parse(scheduled.getCronExpression());
            LocalDateTime cursor = LocalDateTime.ofInstant(until, ZoneId.systemDefault()).minusNanos(1);
            LocalDateTime next = cron.next(cursor);
            scheduled.setNextRunAt(next != null ? next.atZone(ZoneId.systemDefault()).toInstant() : null);
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
