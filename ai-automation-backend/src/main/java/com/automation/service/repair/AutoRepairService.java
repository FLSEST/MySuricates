package com.automation.service.repair;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.factory.WorkflowFactoryService.FactoryTestResult;
import com.automation.service.staging.TestingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRepairService {

    static final String SETTING_AUTO_REPAIR_DEFAULT = "auto-repair-default";
    private static final int DEFAULT_MAX_RETRIES = 2;

    private final WorkflowFactoryService workflowFactoryService;
    private final TestingService testingService;
    private final ActivityLogService activityLogService;
    private final AppSettingsRepository appSettingsRepository;

    @Async
    @EventListener
    public void onWorkflowRunCompleted(WorkflowRunCompletedEvent event) {
        if (event.getStatus() != RunStatus.FAILED) {
            return;
        }

        // Loop protection: skip if this run was itself an auto-repair attempt
        Object autoRepairAttempt = event.getRunMetadata().get("autoRepairAttempt");
        if (Boolean.TRUE.equals(autoRepairAttempt)) {
            log.debug("Skipping auto-repair for run {} (already a repair attempt)", event.getRunId());
            return;
        }

        if (!isAutoRepairEnabled(event)) {
            log.debug("Auto-repair disabled for workflow {}", event.getWorkflowId());
            return;
        }

        if (!testingService.isHealthy()) {
            log.debug("Auto-repair skipped: test instance not healthy (workflow {})", event.getWorkflowId());
            return;
        }

        String workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();

        activityLogService.info(LogCategory.EXECUTION,
                "Auto-repair started for workflow: " + workflowName,
                "AutoRepairService", event.getRunId(), workflowId);

        try {
            FactoryTestResult result = workflowFactoryService.verifyAndFix(workflowId, DEFAULT_MAX_RETRIES);

            if ("PASSED".equals(result.status())) {
                activityLogService.info(LogCategory.EXECUTION,
                        "Auto-repair succeeded for workflow: " + workflowName,
                        "AutoRepairService", result.runId(), workflowId);
                log.info("Auto-repair succeeded for workflow '{}' ({})", workflowName, workflowId);
            } else {
                activityLogService.warn(LogCategory.EXECUTION,
                        "Auto-repair failed for workflow: " + workflowName + " — " + result.error(),
                        "AutoRepairService", result.runId(), workflowId);
                log.warn("Auto-repair failed for workflow '{}': {}", workflowName, result.error());
            }
        } catch (Exception e) {
            activityLogService.error(LogCategory.EXECUTION,
                    "Auto-repair error for workflow: " + workflowName + " — " + e.getMessage(),
                    "AutoRepairService", event.getRunId(), workflowId);
            log.error("Auto-repair error for workflow '{}': {}", workflowName, e.getMessage(), e);
        }
    }

    @SuppressWarnings("unchecked")
    boolean isAutoRepairEnabled(WorkflowRunCompletedEvent event) {
        // Priority 1: explicit workflow metadata
        Object autoRepair = event.getWorkflowMetadata().get("autoRepair");
        if (autoRepair instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            if (enabled instanceof Boolean b) {
                return b;
            }
        }
        // Priority 2: global default setting
        return appSettingsRepository.get(SETTING_AUTO_REPAIR_DEFAULT)
                .map("true"::equals)
                .orElse(false);
    }
}
