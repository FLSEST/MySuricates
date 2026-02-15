package com.automation.service.scheduler;

import com.automation.entity.ScheduledWorkflow;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.workflow.WorkflowExecutor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

@Service
@RequiredArgsConstructor
@Slf4j
public class ScheduledWorkflowExecutor {

    private final ScheduledWorkflowRepository scheduledRepository;
    private final WorkflowExecutor workflowExecutor;

    @Transactional
    public void executeSingleSchedule(String scheduleId) {
        ScheduledWorkflow scheduled = scheduledRepository.findById(scheduleId).orElse(null);

        if (scheduled == null || !scheduled.isEnabled()
                || scheduled.getNextRunAt() == null
                || scheduled.getNextRunAt().isAfter(Instant.now())) {
            return;
        }

        log.info("Executing scheduled workflow: {} (cron: {})",
                scheduled.getId(), scheduled.getCronExpression());

        workflowExecutor.execute(scheduled.getWorkflow(), scheduled.getInputContext());

        scheduled.setLastRunAt(Instant.now());
        scheduled.setNextRunAt(calculateNextRun(scheduled.getCronExpression()));
        scheduledRepository.save(scheduled);

        log.info("Scheduled workflow {} executed successfully, next run at: {}",
                scheduled.getId(), scheduled.getNextRunAt());
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
