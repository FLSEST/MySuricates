package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ChainRun;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowChainRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.dto.response.UpcomingScheduleResponse;
import com.automation.repository.AppSettingsRepository;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final WorkflowRepository workflowRepository;
    private final WorkflowChainRepository workflowChainRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final ScheduledWorkflowExecutor scheduledExecutor;
    private final ActivityLogService activityLogService;
    private final AppSettingsRepository appSettingsRepository;
    private final SchedulerConfig schedulerConfig;
    private final SuspendState suspendState;
    private final Clock clock;

    private static final String MAINTENANCE_MODE_KEY = "scheduler.maintenance-mode";

    /** Last unparseable skip-until value we already warned about, to avoid logging every 60s tick. */
    private volatile String lastInvalidSkipUntil = null;

    /** Wall-clock time of the previous scheduler tick; a large gap signals a system resume. */
    private Instant lastTickAt = null;

    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findAll() {
        return scheduledRepository.findAll();
    }

    @Transactional(readOnly = true)
    public ScheduledWorkflow findById(String id) {
        return scheduledRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("ScheduledWorkflow", id));
    }

    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findByWorkflowId(String workflowId) {
        return scheduledRepository.findByWorkflowId(workflowId);
    }

    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findByChainId(String chainId) {
        return scheduledRepository.findByChainId(chainId);
    }

    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findSchedulesInRange(Instant from, Instant to) {
        return scheduledRepository.findSchedulesInRange(from, to);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRun> findWorkflowRunsByScheduleId(String scheduleId) {
        return workflowRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(scheduleId);
    }

    @Transactional(readOnly = true)
    public List<ChainRun> findChainRunsByScheduleId(String scheduleId) {
        return chainRunRepository.findByTriggeredByScheduleIdOrderByStartedAtDesc(scheduleId);
    }

    @Transactional(readOnly = true)
    public List<WorkflowRun> findWorkflowRunsBetween(Instant from, Instant to) {
        return workflowRunRepository.findByStartedAtBetween(from, to);
    }

    @Transactional
    public ScheduledWorkflow create(String workflowId, String chainId,
                                     String targetTypeStr, String cronExpression,
                                     String description, Map<String, Object> inputContext,
                                     String scheduleType, Instant scheduledAt,
                                     String delayExpression, Instant endDate, Integer maxRuns,
                                     String conflictBehaviorStr, Boolean catchUpOnResume) {
        TargetType targetType = parseTargetType(targetTypeStr);

        // Validate mutual exclusivity
        boolean hasWorkflow = workflowId != null && !workflowId.isBlank();
        boolean hasChain = chainId != null && !chainId.isBlank();

        if (targetType == TargetType.CHAIN) {
            if (!hasChain) {
                throw new IllegalArgumentException("chainId is required for CHAIN schedules");
            }
        } else {
            if (!hasWorkflow) {
                throw new IllegalArgumentException("workflowId is required for WORKFLOW schedules");
            }
        }

        ScheduleType type = parseScheduleType(scheduleType);

        ScheduledWorkflow.ConflictBehavior conflictBehavior = ScheduledWorkflow.ConflictBehavior.SKIP;
        if (conflictBehaviorStr != null && !conflictBehaviorStr.isBlank()) {
            try {
                conflictBehavior = ScheduledWorkflow.ConflictBehavior.valueOf(conflictBehaviorStr.toUpperCase());
            } catch (IllegalArgumentException ignored) {}
        }

        ScheduledWorkflow.ScheduledWorkflowBuilder builder = ScheduledWorkflow.builder()
                .targetType(targetType)
                .description(description)
                .inputContext(inputContext != null ? inputContext : Map.of())
                .enabled(true)
                .scheduleType(type)
                .endDate(endDate)
                .maxRuns(maxRuns != null ? maxRuns : 0)
                .conflictBehavior(conflictBehavior)
                .catchUpOnResume(catchUpOnResume != null && catchUpOnResume);

        if (targetType == TargetType.CHAIN) {
            WorkflowChain chain = workflowChainRepository.findById(chainId)
                    .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId));
            builder.chain(chain);
        } else {
            Workflow workflow = workflowRepository.findById(workflowId)
                    .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));
            builder.workflow(workflow);
        }

        if (type == ScheduleType.ONCE) {
            if (scheduledAt == null) {
                throw new IllegalArgumentException("scheduledAt is required for ONCE schedules");
            }
            builder.cronExpression("");
            builder.scheduledAt(scheduledAt);
            builder.nextRunAt(scheduledAt);
        } else if (type == ScheduleType.DELAYED) {
            if (delayExpression == null || delayExpression.isBlank()) {
                throw new IllegalArgumentException("delayExpression is required for DELAYED schedules (e.g. '2h', '30m', '1d')");
            }
            Instant runAt = Instant.now(clock).plus(parseDelay(delayExpression));
            builder.cronExpression("");
            builder.scheduledAt(runAt);
            builder.nextRunAt(runAt);
            builder.delayExpression(delayExpression);
        } else {
            if (cronExpression == null || cronExpression.isBlank()) {
                throw new IllegalArgumentException("cronExpression is required for CRON schedules");
            }
            if (!CronExpression.isValidExpression(cronExpression)) {
                throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
            }
            builder.cronExpression(cronExpression);
            builder.nextRunAt(calculateNextRun(cronExpression));
        }

        ScheduledWorkflow saved = scheduledRepository.save(builder.build());
        log.info("Created {} schedule: {} for {} {}",
                type, saved.getId(), targetType,
                targetType == TargetType.CHAIN ? chainId : workflowId);
        return saved;
    }

    @Transactional
    public ScheduledWorkflow update(String id, UpdateScheduleRequest request) {
        ScheduledWorkflow scheduled = findById(id);

        if (request.description() != null) {
            scheduled.setDescription(request.description());
        }
        if (request.inputContext() != null) {
            scheduled.setInputContext(request.inputContext());
        }
        if (request.catchUpOnResume() != null) {
            scheduled.setCatchUpOnResume(request.catchUpOnResume());
        }

        if (scheduled.getScheduleType() == ScheduleType.CRON && request.cronExpression() != null) {
            if (!CronExpression.isValidExpression(request.cronExpression())) {
                throw new IllegalArgumentException("Invalid cron expression: " + request.cronExpression());
            }
            scheduled.setCronExpression(request.cronExpression());
            if (scheduled.isEnabled()) {
                scheduled.setNextRunAt(calculateNextRun(request.cronExpression()));
            }
        }

        if (scheduled.getScheduleType() == ScheduleType.ONCE && request.scheduledAt() != null) {
            scheduled.setScheduledAt(request.scheduledAt());
            if (scheduled.isEnabled()) {
                scheduled.setNextRunAt(request.scheduledAt());
            }
        }

        if (scheduled.getScheduleType() == ScheduleType.CRON) {
            if (request.endDate() != null) {
                scheduled.setEndDate(request.endDate());
            }
            if (request.maxRuns() != null) {
                scheduled.setMaxRuns(request.maxRuns());
            }
        }

        log.info("Updated schedule: {}", id);
        return scheduledRepository.save(scheduled);
    }

    @Transactional
    public ScheduledWorkflow enable(String id) {
        ScheduledWorkflow scheduled = findById(id);
        scheduled.setEnabled(true);

        if (scheduled.getScheduleType() == ScheduleType.ONCE
                || scheduled.getScheduleType() == ScheduleType.DELAYED) {
            scheduled.setNextRunAt(scheduled.getScheduledAt());
        } else {
            scheduled.setNextRunAt(calculateNextRun(scheduled.getCronExpression()));
        }

        return scheduledRepository.save(scheduled);
    }

    @Transactional
    public ScheduledWorkflow disable(String id) {
        ScheduledWorkflow scheduled = findById(id);
        scheduled.setEnabled(false);
        scheduled.setNextRunAt(null);
        return scheduledRepository.save(scheduled);
    }

    @Transactional
    public void delete(String id) {
        ScheduledWorkflow scheduled = findById(id);
        scheduledRepository.delete(scheduled);
        log.info("Deleted scheduled workflow: {}", id);
    }

    @Transactional(readOnly = true)
    public UpcomingScheduleResponse getRemainingToday() {
        Instant endOfDay = LocalDate.now(clock).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfDay);
    }

    @Transactional(readOnly = true)
    public UpcomingScheduleResponse getRemainingThisWeek() {
        LocalDate nextMonday = LocalDate.now(clock).with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Instant endOfWeek = nextMonday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfWeek);
    }

    private UpcomingScheduleResponse getUpcomingRuns(Instant until) {
        Instant now = Instant.now(clock);
        List<ScheduledWorkflow> enabled = scheduledRepository.findByEnabledTrue();
        List<UpcomingScheduleResponse.UpcomingRun> upcoming = new ArrayList<>();

        for (ScheduledWorkflow sw : enabled) {
            String workflowName = null;
            String projectName = null;
            String chainId = null;
            String chainName = null;
            String targetType = sw.getTargetType().name();

            if (sw.getTargetType() == TargetType.CHAIN && sw.getChain() != null) {
                chainId = sw.getChain().getId();
                chainName = sw.getChain().getName();
            } else if (sw.getWorkflow() != null) {
                workflowName = sw.getWorkflow().getName();
                if (sw.getWorkflow().getProject() != null) {
                    projectName = sw.getWorkflow().getProject().getName();
                }
            }

            if (sw.getScheduleType() == ScheduleType.CRON) {
                int remainingRuns = getRemainingRunLimit(sw);
                if (remainingRuns == 0) {
                    continue;
                }
                Instant effectiveUntil = getEffectiveUntil(until, sw.getEndDate());
                CronExpression cron = CronExpression.parse(sw.getCronExpression());
                LocalDateTime next = cron.next(LocalDateTime.now(clock));
                int addedForSchedule = 0;
                while (next != null && addedForSchedule < remainingRuns) {
                    Instant nextInstant = next.atZone(ZoneId.systemDefault()).toInstant();
                    if (!nextInstant.isBefore(effectiveUntil)) break;
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName,
                            chainId, chainName, targetType, nextInstant));
                    addedForSchedule++;
                    next = cron.next(next);
                }
            } else if (sw.getScheduleType() == ScheduleType.ONCE
                    || sw.getScheduleType() == ScheduleType.DELAYED) {
                if (sw.getScheduledAt() != null
                        && sw.getScheduledAt().isAfter(now)
                        && sw.getScheduledAt().isBefore(until)) {
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName,
                            chainId, chainName, targetType, sw.getScheduledAt()));
                }
            }
        }

        return new UpcomingScheduleResponse(upcoming.size(), upcoming);
    }

    private int getRemainingRunLimit(ScheduledWorkflow sw) {
        if (sw.getMaxRuns() <= 0) {
            return Integer.MAX_VALUE;
        }
        return Math.max(0, sw.getMaxRuns() - sw.getRunCount());
    }

    private Instant getEffectiveUntil(Instant until, Instant endDate) {
        if (endDate == null || endDate.isAfter(until)) {
            return until;
        }
        return endDate;
    }

    public List<Instant> getNextRuns(String cronExpression, int count) {
        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }
        CronExpression cron = CronExpression.parse(cronExpression);
        List<Instant> result = new ArrayList<>();
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        while (next != null && result.size() < count) {
            result.add(next.atZone(ZoneId.systemDefault()).toInstant());
            next = cron.next(next);
        }
        return result;
    }

    public boolean isMaintenanceMode() {
        return appSettingsRepository.get(MAINTENANCE_MODE_KEY)
                .map("true"::equals).orElse(false);
    }

    public void setMaintenanceMode(boolean enabled) {
        appSettingsRepository.set(MAINTENANCE_MODE_KEY, String.valueOf(enabled));
        log.info("Scheduler maintenance mode {}", enabled ? "enabled" : "disabled");
    }

    @Scheduled(fixedRate = 60_000)
    public void checkAndExecuteScheduled() {
        Instant now = Instant.now(clock);
        Instant prevTick = lastTickAt;
        lastTickAt = now;

        if (isMaintenanceMode()) {
            log.debug("Scheduler in maintenance mode — skipping execution");
            return;
        }

        // System-resume detection: a tick that fired far later than the fixed 60s rate means the
        // JVM was frozen (e.g. laptop suspend). Handle the accumulated backlog via each schedule's
        // catch-up policy instead of firing it all at once (which spawned a stampede of runs that
        // were then reaped as ghosts when the subprocesses had died across the suspend).
        if (prevTick != null && schedulerConfig.getResumeGraceMinutes() > 0
                && Duration.between(prevTick, now)
                        .compareTo(Duration.ofMinutes(schedulerConfig.getResumeGraceMinutes())) > 0) {
            handleResume(prevTick, now);
            return;
        }

        Optional<Instant> skipUntil = parseSkipUntil();
        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(now);

        if (skipUntil.isPresent() && now.isBefore(skipUntil.get())) {
            Instant until = skipUntil.get();
            for (ScheduledWorkflow scheduled : dueSchedules) {
                skipScheduleUntil(scheduled, until);
            }
            return;
        }

        for (ScheduledWorkflow scheduled : dueSchedules) {
            try {
                scheduledExecutor.executeSingleSchedule(scheduled.getId());
            } catch (Exception e) {
                log.error("Failed to execute scheduled workflow: {}", scheduled.getId(), e);
                activityLogService.error(LogCategory.SCHEDULE,
                        "Schedule execution failed: " + e.getMessage(),
                        "SchedulerService", scheduled.getId());
            }
        }
    }

    /**
     * Handle the due-schedule backlog after a detected system resume. Schedules with
     * {@code catchUpOnResume=true} run once; all others are skipped forward to their next future
     * occurrence (and an ActivityLog entry records the suspend-skip), so a weekend of missed daily
     * radars/audits does not fire a simultaneous stampede on Monday morning.
     */
    private void handleResume(Instant prevTick, Instant now) {
        long gapMinutes = Duration.between(prevTick, now).toMinutes();
        suspendState.recordResume(now, Duration.between(prevTick, now).toMillis());

        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(now);
        log.warn("System resume detected (paused ~{} min) — applying catch-up policy to {} due schedule(s)",
                gapMinutes, dueSchedules.size());
        activityLogService.warn(LogCategory.SCHEDULE,
                "System resume detected (paused ~" + gapMinutes + " min) — applying catch-up policy to "
                        + dueSchedules.size() + " due schedule(s)",
                "SchedulerService");

        for (ScheduledWorkflow scheduled : dueSchedules) {
            if (scheduled.isCatchUpOnResume()) {
                try {
                    scheduledExecutor.executeSingleSchedule(scheduled.getId());
                } catch (Exception e) {
                    log.error("Failed to catch up scheduled workflow on resume: {}", scheduled.getId(), e);
                    activityLogService.error(LogCategory.SCHEDULE,
                            "Schedule catch-up failed: " + e.getMessage(),
                            "SchedulerService", scheduled.getId());
                }
            } else {
                skipScheduleForResume(scheduled, now, gapMinutes);
            }
        }
    }

    private Optional<Instant> parseSkipUntil() {
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
    private void skipScheduleUntil(ScheduledWorkflow scheduled, Instant until) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("skipUntil", until.toString());
        skipSchedule(scheduled, until, "scheduler-skip-until",
                "Schedule skipped: " + scheduled.getTargetName(), extra);
    }

    /** Skip a schedule forward because the system resumed after a long suspend/freeze. */
    private void skipScheduleForResume(ScheduledWorkflow scheduled, Instant now, long gapMinutes) {
        Map<String, Object> extra = new HashMap<>();
        extra.put("suspendGapMinutes", gapMinutes);
        skipSchedule(scheduled, now, "suspend-resume",
                "Schedule skipped (system suspend/resume): " + scheduled.getTargetName(), extra);
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

    private TargetType parseTargetType(String targetType) {
        if (targetType == null || targetType.isBlank()) {
            return TargetType.WORKFLOW;
        }
        try {
            return TargetType.valueOf(targetType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid target type: " + targetType);
        }
    }

    private ScheduleType parseScheduleType(String scheduleType) {
        if (scheduleType == null || scheduleType.isBlank()) {
            return ScheduleType.CRON;
        }
        try {
            return ScheduleType.valueOf(scheduleType.toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid schedule type: " + scheduleType);
        }
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now(clock));
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }

    /**
     * Parses a human-readable delay expression into a {@link Duration}.
     * Supported formats: {@code 30s} (seconds), {@code 10m} (minutes), {@code 2h} (hours), {@code 1d} (days).
     */
    static Duration parseDelay(String expr) {
        if (expr == null || expr.isBlank()) {
            throw new IllegalArgumentException("Delay expression must not be empty");
        }
        String trimmed = expr.trim().toLowerCase();
        try {
            if (trimmed.endsWith("s")) return Duration.ofSeconds(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("m")) return Duration.ofMinutes(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("h")) return Duration.ofHours(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
            if (trimmed.endsWith("d")) return Duration.ofDays(Long.parseLong(trimmed.substring(0, trimmed.length() - 1)));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid delay expression: '" + expr + "'. Expected format: 30s, 10m, 2h, 1d");
        }
        throw new IllegalArgumentException("Invalid delay expression: '" + expr + "'. Supported suffixes: s, m, h, d");
    }
}
