package com.automation.service.scheduler;

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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.automation.dto.request.UpdateScheduleRequest;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Schedule CRUD (create/update/enable/disable/delete) and run lookups per schedule.
 * The 60s tick lives in {@link SchedulerTick}, the maintenance switch in
 * {@link SchedulerMaintenanceMode}, calendar projections in {@link UpcomingRunsCalculator},
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
    private final Clock clock;
    private final ScheduleDefinitionParser definitionParser;

    /**
     * Die Liste fuer {@code GET /api/v1/schedules} — mit mitgeholten Zielen.
     *
     * <p>Bewusst nicht {@code findAll()}: Das wuerde die {@code EAGER}-Assoziationen je Zeile
     * einzeln nachladen (TODO c9ec9844).
     */
    @Transactional(readOnly = true)
    public List<ScheduledWorkflow> findAll() {
        return scheduledRepository.findAllWithTargets();
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
        requireTarget(targetType, workflowId, chainId);

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

        resolveTarget(builder, targetType, workflowId, chainId);

        applyTiming(builder, type, cronExpression, scheduledAt, delayExpression);

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
        if (request.conflictBehavior() != null && !request.conflictBehavior().isBlank()) {
            scheduled.setConflictBehavior(definitionParser.parseConflictBehavior(request.conflictBehavior()));
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

    private void requireTarget(TargetType targetType, String workflowId, String chainId) {
        if (targetType == TargetType.CHAIN) {
            if (chainId == null || chainId.isBlank()) {
                throw new IllegalArgumentException("chainId is required for CHAIN schedules");
            }
        } else {
            if (workflowId == null || workflowId.isBlank()) {
                throw new IllegalArgumentException("workflowId is required for WORKFLOW schedules");
            }
        }
    }

    private void resolveTarget(ScheduledWorkflow.ScheduledWorkflowBuilder builder, TargetType targetType,
                               String workflowId, String chainId) {
        if (targetType == TargetType.CHAIN) {
            builder.chain(workflowChainRepository.findById(chainId)
                    .orElseThrow(() -> new ResourceNotFoundException("WorkflowChain", chainId)));
        } else {
            builder.workflow(workflowRepository.findById(workflowId)
                    .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId)));
        }
    }

    private void applyTiming(ScheduledWorkflow.ScheduledWorkflowBuilder builder, ScheduleType type,
                             String cronExpression, Instant scheduledAt, String delayExpression) {
        switch (type) {
            case ONCE -> {
                if (scheduledAt == null) {
                    throw new IllegalArgumentException("scheduledAt is required for ONCE schedules");
                }
                builder.cronExpression("").scheduledAt(scheduledAt).nextRunAt(scheduledAt);
            }
            case DELAYED -> {
                if (delayExpression == null || delayExpression.isBlank()) {
                    throw new IllegalArgumentException("delayExpression is required for DELAYED schedules (e.g. '2h', '30m', '1d')");
                }
                Instant runAt = Instant.now(clock).plus(ScheduleDefinitionParser.parseDelay(delayExpression));
                builder.cronExpression("").scheduledAt(runAt).nextRunAt(runAt).delayExpression(delayExpression);
            }
            default -> {
                if (cronExpression == null || cronExpression.isBlank()) {
                    throw new IllegalArgumentException("cronExpression is required for CRON schedules");
                }
                if (!CronExpression.isValidExpression(cronExpression)) {
                    throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
                }
                builder.cronExpression(cronExpression).nextRunAt(definitionParser.calculateNextRun(cronExpression));
            }
        }
    }
}
