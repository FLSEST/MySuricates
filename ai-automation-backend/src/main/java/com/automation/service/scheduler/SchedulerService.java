package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchedulerService {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final WorkflowRepository workflowRepository;
    private final ScheduledWorkflowExecutor scheduledExecutor;

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

    @Transactional
    public ScheduledWorkflow create(String workflowId, String cronExpression,
                                     String description, Map<String, Object> inputContext) {
        Workflow workflow = workflowRepository.findById(workflowId)
                .orElseThrow(() -> new ResourceNotFoundException("Workflow", workflowId));

        if (!CronExpression.isValidExpression(cronExpression)) {
            throw new IllegalArgumentException("Invalid cron expression: " + cronExpression);
        }

        Instant nextRun = calculateNextRun(cronExpression);

        ScheduledWorkflow scheduled = ScheduledWorkflow.builder()
                .workflow(workflow)
                .cronExpression(cronExpression)
                .description(description)
                .inputContext(inputContext != null ? inputContext : Map.of())
                .enabled(true)
                .nextRunAt(nextRun)
                .build();

        ScheduledWorkflow saved = scheduledRepository.save(scheduled);
        log.info("Created scheduled workflow: {} for workflow {} with cron '{}'",
                saved.getId(), workflowId, cronExpression);
        return saved;
    }

    @Transactional
    public ScheduledWorkflow enable(String id) {
        ScheduledWorkflow scheduled = findById(id);
        scheduled.setEnabled(true);
        scheduled.setNextRunAt(calculateNextRun(scheduled.getCronExpression()));
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
            }
        }
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
