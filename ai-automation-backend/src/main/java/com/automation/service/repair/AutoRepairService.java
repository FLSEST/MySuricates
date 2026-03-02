package com.automation.service.repair;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.factory.WorkflowFactoryService.FactoryTestResult;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AutoRepairService {

    static final String SETTING_AUTO_REPAIR_DEFAULT = "auto-repair-default";
    private static final int DEFAULT_MAX_RETRIES = 2;

    private final WorkflowFactoryService workflowFactoryService;
    private final WorkflowService workflowService;
    private final TestingService testingService;
    private final ActivityLogService activityLogService;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowRunRepository workflowRunRepository;

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

    /**
     * Retroactively repair a single workflow by ID.
     * Checks for FAILED runs and delegates to verifyAndFix.
     */
    @Async
    public void repairWorkflow(String workflowId) {
        Workflow workflow = workflowService.findById(workflowId);

        activityLogService.info(LogCategory.EXECUTION,
                "Retroactive repair started for workflow: " + workflow.getName(),
                "AutoRepairService", null, workflowId);

        try {
            FactoryTestResult result = workflowFactoryService.verifyAndFix(workflowId, DEFAULT_MAX_RETRIES);

            if ("PASSED".equals(result.status())) {
                activityLogService.info(LogCategory.EXECUTION,
                        "Retroactive repair succeeded for workflow: " + workflow.getName(),
                        "AutoRepairService", result.runId(), workflowId);
                log.info("Retroactive repair succeeded for workflow '{}' ({})", workflow.getName(), workflowId);
            } else {
                activityLogService.warn(LogCategory.EXECUTION,
                        "Retroactive repair failed for workflow: " + workflow.getName() + " — " + result.error(),
                        "AutoRepairService", result.runId(), workflowId);
                log.warn("Retroactive repair failed for workflow '{}': {}", workflow.getName(), result.error());
            }
        } catch (Exception e) {
            activityLogService.error(LogCategory.EXECUTION,
                    "Retroactive repair error for workflow: " + workflow.getName() + " — " + e.getMessage(),
                    "AutoRepairService", null, workflowId);
            log.error("Retroactive repair error for workflow '{}': {}", workflow.getName(), e.getMessage(), e);
        }
    }

    /**
     * Repair all workflows that have at least one FAILED run.
     * @param force if true, repairs all; if false, only workflows with autoRepair.enabled metadata
     * @return number of workflows queued for repair
     */
    public int repairAllFailed(boolean force) {
        List<String> failedWorkflowIds = workflowRunRepository.findDistinctWorkflowIdsWithFailedRuns();

        if (failedWorkflowIds.isEmpty()) {
            log.info("Retroactive repair: no workflows with failed runs found");
            return 0;
        }

        int queued = 0;
        for (String workflowId : failedWorkflowIds) {
            try {
                Workflow workflow = workflowService.findById(workflowId);

                if (!force && !isAutoRepairEnabledForWorkflow(workflow)) {
                    log.debug("Retroactive repair skipped for workflow '{}' (autoRepair not enabled)", workflow.getName());
                    continue;
                }

                repairWorkflow(workflowId);
                queued++;
            } catch (Exception e) {
                log.warn("Retroactive repair: skipping workflow {}: {}", workflowId, e.getMessage());
            }
        }

        activityLogService.info(LogCategory.EXECUTION,
                "Retroactive repair batch started: " + queued + " of " + failedWorkflowIds.size() + " workflows queued",
                "AutoRepairService", null, null);
        log.info("Retroactive repair: queued {} of {} workflows", queued, failedWorkflowIds.size());
        return queued;
    }

    @SuppressWarnings("unchecked")
    private boolean isAutoRepairEnabledForWorkflow(Workflow workflow) {
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata == null) return false;

        Object autoRepair = metadata.get("autoRepair");
        if (autoRepair instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            if (enabled instanceof Boolean b) {
                return b;
            }
        }
        // Fall back to global default
        return appSettingsRepository.get(SETTING_AUTO_REPAIR_DEFAULT)
                .map("true"::equals)
                .orElse(false);
    }

    public boolean isTestInstanceHealthy() {
        return testingService.isHealthy();
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
