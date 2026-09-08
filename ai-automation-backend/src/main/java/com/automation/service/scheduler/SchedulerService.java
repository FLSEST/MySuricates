package com.automation.service.scheduler;

import com.automation.config.SchedulerConfig;
import com.automation.entity.ActivityLog.LogCategory;
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
import com.automation.repository.AppSettingsRepository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Schedule CRUD, maintenance mode and the 60s scheduler tick (due schedules, skip-until window,
 * suspend/resume catch-up). Calendar projections live in {@link UpcomingRunsCalculator},
 * skip mechanics in {@link ScheduleSkipPolicy}, field parsing in {@link ScheduleDefinitionParser}.
 */
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
    private final ScheduleDefinitionParser definitionParser;
    private final ScheduleSkipPolicy skipPolicy;

    private static final String MAINTENANCE_MODE_KEY = "scheduler.maintenance-mode";

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
        TargetType targetType = definitionParser.parseTargetType(targetTypeStr);

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

        ScheduleType type = definitionParser.parseScheduleType(scheduleType);

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
            Instant runAt = Instant.now(clock).plus(ScheduleDefinitionParser.parseDelay(delayExpression));
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
            builder.nextRunAt(definitionParser.calculateNextRun(cronExpression));
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
                scheduled.setNextRunAt(definitionParser.calculateNextRun(request.cronExpression()));
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
            scheduled.setNextRunAt(definitionParser.calculateNextRun(scheduled.getCronExpression()));
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

        Optional<Instant> skipUntil = skipPolicy.parseSkipUntil();
        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(now);

        if (skipUntil.isPresent() && now.isBefore(skipUntil.get())) {
            Instant until = skipUntil.get();
            for (ScheduledWorkflow scheduled : dueSchedules) {
                skipPolicy.skipScheduleUntil(scheduled, until);
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
            if (scheduled.isCatchUpOnResume() || schedulerConfig.isCatchUpOnResume()) {
                try {
                    scheduledExecutor.executeSingleSchedule(scheduled.getId());
                } catch (Exception e) {
                    log.error("Failed to catch up scheduled workflow on resume: {}", scheduled.getId(), e);
                    activityLogService.error(LogCategory.SCHEDULE,
                            "Schedule catch-up failed: " + e.getMessage(),
                            "SchedulerService", scheduled.getId());
                }
            } else {
                skipPolicy.skipScheduleForResume(scheduled, now, gapMinutes);
            }
        }
    }
}
