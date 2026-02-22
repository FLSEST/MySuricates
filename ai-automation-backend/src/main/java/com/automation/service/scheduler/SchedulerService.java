package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.automation.dto.response.UpcomingScheduleResponse;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final WorkflowRepository workflowRepository;
    private final ScheduledWorkflowExecutor scheduledExecutor;
    private final ActivityLogService activityLogService;

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
    public List<ScheduledWorkflow> findSchedulesInRange(Instant from, Instant to) {
        return scheduledRepository.findSchedulesInRange(from, to);
    }

    @Transactional
    public ScheduledWorkflow create(String workflowId, String cronExpression,
                                     String description, Map<String, Object> inputContext,
                                     String scheduleType, Instant scheduledAt) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        ScheduleType type = parseScheduleType(scheduleType);

        ScheduledWorkflow.ScheduledWorkflowBuilder builder = ScheduledWorkflow.builder()
                .workflow(workflow)
                .description(description)
                .inputContext(inputContext != null ? inputContext : Map.of())
                .enabled(true)
                .scheduleType(type);

        if (type == ScheduleType.ONCE) {
            if (scheduledAt == null) {
                throw new IllegalArgumentException("scheduledAt is required for ONCE schedules");
            }
            builder.cronExpression("");
            builder.scheduledAt(scheduledAt);
            builder.nextRunAt(scheduledAt);
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
        log.info("Created {} schedule: {} for workflow {}",
                type, saved.getId(), workflowId);
        return saved;
    }

    @Transactional
    public ScheduledWorkflow enable(String id) {
        ScheduledWorkflow scheduled = findById(id);
        scheduled.setEnabled(true);

        if (scheduled.getScheduleType() == ScheduleType.ONCE) {
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
        Instant endOfDay = LocalDate.now().plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfDay);
    }

    @Transactional(readOnly = true)
    public UpcomingScheduleResponse getRemainingThisWeek() {
        // End of week = next Monday 00:00 (week starts on Monday)
        LocalDate nextMonday = LocalDate.now().with(TemporalAdjusters.next(DayOfWeek.MONDAY));
        Instant endOfWeek = nextMonday.atStartOfDay(ZoneId.systemDefault()).toInstant();
        return getUpcomingRuns(endOfWeek);
    }

    private UpcomingScheduleResponse getUpcomingRuns(Instant until) {
        Instant now = Instant.now();
        List<ScheduledWorkflow> enabled = scheduledRepository.findByEnabledTrue();
        List<UpcomingScheduleResponse.UpcomingRun> upcoming = new ArrayList<>();

        for (ScheduledWorkflow sw : enabled) {
            String workflowName = sw.getWorkflow() != null ? sw.getWorkflow().getName() : "Unknown";
            String projectName = sw.getWorkflow() != null && sw.getWorkflow().getProject() != null
                    ? sw.getWorkflow().getProject().getName() : null;

            if (sw.getScheduleType() == ScheduledWorkflow.ScheduleType.CRON) {
                CronExpression cron = CronExpression.parse(sw.getCronExpression());
                LocalDateTime next = cron.next(LocalDateTime.now());
                while (next != null) {
                    Instant nextInstant = next.atZone(ZoneId.systemDefault()).toInstant();
                    if (nextInstant.isAfter(until)) break;
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName, nextInstant));
                    next = cron.next(next);
                }
            } else if (sw.getScheduleType() == ScheduledWorkflow.ScheduleType.ONCE) {
                if (sw.getScheduledAt() != null
                        && sw.getScheduledAt().isAfter(now)
                        && sw.getScheduledAt().isBefore(until)) {
                    upcoming.add(new UpcomingScheduleResponse.UpcomingRun(
                            sw.getId(), workflowName, projectName, sw.getScheduledAt()));
                }
            }
        }

        return new UpcomingScheduleResponse(upcoming.size(), upcoming);
    }

    /**
     * Runs every minute to check for due scheduled workflows.
     * Delegates to ScheduledWorkflowExecutor (separate bean) so that
     * @Transactional on executeSingleSchedule is properly proxied.
     */
    @Scheduled(fixedRate = 60_000)
    public void checkAndExecuteScheduled() {
        List<ScheduledWorkflow> dueSchedules = scheduledRepository.findDueSchedules(Instant.now());

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
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
