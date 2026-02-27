package com.automation.service.scheduler;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.ScheduledWorkflow.ScheduleType;
import com.automation.entity.ScheduledWorkflow.TargetType;
import com.automation.repository.ScheduledWorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.chain.ChainExecutor;
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
    private final ChainExecutor chainExecutor;
    private final ActivityLogService activityLogService;

    @Transactional
    public void executeSingleSchedule(String scheduleId) {
        ScheduledWorkflow scheduled = scheduledRepository.findById(scheduleId).orElse(null);

        if (scheduled == null || !scheduled.isEnabled()
                || scheduled.getNextRunAt() == null
                || scheduled.getNextRunAt().isAfter(Instant.now())) {
            return;
        }

        log.info("Executing scheduled {}: {} (type: {})",
                scheduled.getTargetType(), scheduled.getId(), scheduled.getScheduleType());

        activityLogService.info(LogCategory.SCHEDULE,
                "Schedule triggered: " + scheduled.getTargetName(),
                "ScheduledWorkflowExecutor", scheduled.getId());

        if (scheduled.getTargetType() == TargetType.CHAIN && scheduled.getChain() != null) {
            chainExecutor.execute(scheduled.getChain(), scheduled.getInputContext());
        } else if (scheduled.getWorkflow() != null) {
            workflowExecutor.execute(scheduled.getWorkflow(), scheduled.getInputContext());
        } else {
            log.error("Schedule {} has no valid target", scheduled.getId());
            return;
        }

        scheduled.setLastRunAt(Instant.now());

        if (scheduled.getScheduleType() == ScheduleType.ONCE) {
            scheduled.setNextRunAt(null);
            scheduled.setEnabled(false);
            log.info("ONCE schedule {} executed and disabled", scheduled.getId());
        } else {
            scheduled.setNextRunAt(calculateNextRun(scheduled.getCronExpression()));
            log.info("Schedule {} executed, next run at: {}",
                    scheduled.getId(), scheduled.getNextRunAt());
        }

        scheduledRepository.save(scheduled);
    }

    private Instant calculateNextRun(String cronExpression) {
        CronExpression cron = CronExpression.parse(cronExpression);
        LocalDateTime next = cron.next(LocalDateTime.now());
        if (next == null) return null;
        return next.atZone(ZoneId.systemDefault()).toInstant();
    }
}
