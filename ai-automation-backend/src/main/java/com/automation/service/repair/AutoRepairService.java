package com.automation.service.repair;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.RunStatus;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.factory.WorkflowFactoryService.FactoryTestResult;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class AutoRepairService {

    static final String SETTING_AUTO_REPAIR_DEFAULT = "auto-repair-default";
    static final String SETTING_AUTO_REPAIR_MODE = "auto-repair-mode";
    static final String SETTING_MAX_RETRIES = "auto-repair-max-retries";
    static final String SETTING_TIMEOUT_SECONDS = "auto-repair-timeout-seconds";
    private static final int DEFAULT_MAX_RETRIES = 2;
    private static final long DEFAULT_TIMEOUT_SECONDS = 600;
    private static final long POLLING_INTERVAL_MS = 2000;

    private final WorkflowFactoryService workflowFactoryService;
    private final WorkflowService workflowService;
    private final TestingService testingService;
    private final ActivityLogService activityLogService;
    private final AppSettingsRepository appSettingsRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final WorkflowExecutor workflowExecutor;

    public AutoRepairService(WorkflowFactoryService workflowFactoryService,
                             WorkflowService workflowService,
                             TestingService testingService,
                             ActivityLogService activityLogService,
                             AppSettingsRepository appSettingsRepository,
                             WorkflowRunRepository workflowRunRepository,
                             @Lazy WorkflowExecutor workflowExecutor) {
        this.workflowFactoryService = workflowFactoryService;
        this.workflowService = workflowService;
        this.testingService = testingService;
        this.activityLogService = activityLogService;
        this.appSettingsRepository = appSettingsRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.workflowExecutor = workflowExecutor;
    }

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

        String workflowId = event.getWorkflowId();
        String workflowName = event.getWorkflowName();
        Workflow workflow = workflowService.findById(workflowId);
        String mode = resolveRepairMode(workflow);
        int maxRetries = resolveMaxRetries(workflow);
        long timeoutSeconds = resolveTimeoutSeconds(workflow);

        if ("test".equals(mode) && !testingService.isHealthy()) {
            log.debug("Auto-repair skipped: mode=test but test instance not healthy (workflow {})", workflowId);
            return;
        }

        activityLogService.info(LogCategory.EXECUTION,
                "Auto-repair started for workflow: " + workflowName + " (mode: " + mode + ", retries: " + maxRetries + ", timeout: " + timeoutSeconds + "s)",
                "AutoRepairService", event.getRunId(), workflowId);

        try {
            FactoryTestResult result;
            if (shouldUseTestInstance(mode)) {
                result = workflowFactoryService.verifyAndFix(workflowId, maxRetries);
            } else {
                String errorMsg = extractErrorMessage(event.getError());
                result = repairLocally(workflowId, errorMsg, maxRetries, timeoutSeconds);
            }

            logRepairResult(result, workflowName, workflowId);
        } catch (Exception e) {
            activityLogService.error(LogCategory.EXECUTION,
                    "Auto-repair error for workflow: " + workflowName + " — " + e.getMessage(),
                    "AutoRepairService", event.getRunId(), workflowId);
            log.error("Auto-repair error for workflow '{}': {}", workflowName, e.getMessage(), e);
        }
    }

    /**
     * Retroactively repair a single workflow by ID.
     * Uses resolved repair mode (test/local/auto).
     */
    @Async
    public void repairWorkflow(String workflowId) {
        Workflow workflow = workflowService.findById(workflowId);
        String mode = resolveRepairMode(workflow);
        int maxRetries = resolveMaxRetries(workflow);
        long timeoutSeconds = resolveTimeoutSeconds(workflow);

        activityLogService.info(LogCategory.EXECUTION,
                "Retroactive repair started for workflow: " + workflow.getName() + " (mode: " + mode + ", retries: " + maxRetries + ", timeout: " + timeoutSeconds + "s)",
                "AutoRepairService", null, workflowId);

        try {
            FactoryTestResult result;
            if (shouldUseTestInstance(mode)) {
                result = workflowFactoryService.verifyAndFix(workflowId, maxRetries);
            } else {
                String errorMsg = extractLastFailedRunError(workflowId);
                result = repairLocally(workflowId, errorMsg, maxRetries, timeoutSeconds);
            }

            logRepairResult(result, workflow.getName(), workflowId);
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

    public boolean isTestInstanceHealthy() {
        return testingService.isHealthy();
    }

    /**
     * Resolves the effective repair mode for a workflow.
     * Priority: workflow metadata > global setting > default "auto".
     */
    @SuppressWarnings("unchecked")
    String resolveRepairMode(Workflow workflow) {
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata != null) {
            Object autoRepair = metadata.get("autoRepair");
            if (autoRepair instanceof Map<?, ?> map) {
                Object mode = map.get("mode");
                if (mode instanceof String m && List.of("test", "local", "auto").contains(m)) {
                    return m;
                }
            }
        }
        return appSettingsRepository.get(SETTING_AUTO_REPAIR_MODE)
                .filter(m -> List.of("test", "local", "auto").contains(m))
                .orElse("auto");
    }

    /**
     * Resolves max retries for a workflow.
     * Priority: workflow metadata > global setting > default 2.
     */
    @SuppressWarnings("unchecked")
    int resolveMaxRetries(Workflow workflow) {
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata != null) {
            Object autoRepair = metadata.get("autoRepair");
            if (autoRepair instanceof Map<?, ?> map) {
                Object retries = map.get("maxRetries");
                if (retries instanceof Number n && n.intValue() >= 1 && n.intValue() <= 5) {
                    return n.intValue();
                }
            }
        }
        return appSettingsRepository.get(SETTING_MAX_RETRIES)
                .map(s -> { try { return Math.max(1, Math.min(5, Integer.parseInt(s))); } catch (NumberFormatException e) { return DEFAULT_MAX_RETRIES; } })
                .orElse(DEFAULT_MAX_RETRIES);
    }

    /**
     * Resolves timeout in seconds for a workflow.
     * Priority: workflow metadata > global setting > default 600.
     */
    @SuppressWarnings("unchecked")
    long resolveTimeoutSeconds(Workflow workflow) {
        Map<String, Object> metadata = workflow.getMetadata();
        if (metadata != null) {
            Object autoRepair = metadata.get("autoRepair");
            if (autoRepair instanceof Map<?, ?> map) {
                Object timeout = map.get("timeoutSeconds");
                if (timeout instanceof Number n && n.longValue() >= 60 && n.longValue() <= 3600) {
                    return n.longValue();
                }
            }
        }
        return appSettingsRepository.get(SETTING_TIMEOUT_SECONDS)
                .map(s -> { try { return Math.max(60, Math.min(3600, Long.parseLong(s))); } catch (NumberFormatException e) { return DEFAULT_TIMEOUT_SECONDS; } })
                .orElse(DEFAULT_TIMEOUT_SECONDS);
    }

    // ── Local repair pipeline ──────────────────────────────

    /**
     * Repairs a workflow locally: AI-fix the blocks, then re-execute on this instance.
     */
    FactoryTestResult repairLocally(String workflowId, String errorMessage, int maxRetries, long timeoutSeconds) {
        Workflow workflow = workflowService.findById(workflowId);
        String lastError = errorMessage;

        for (int i = 0; i < maxRetries; i++) {
            log.info("Local repair: attempt {}/{} for workflow '{}'", i + 1, maxRetries, workflow.getName());

            try {
                workflowFactoryService.autoFixWorkflow(workflow, lastError);
                workflow = workflowService.findById(workflowId);

                Map<String, Object> context = workflow.getDefaultContext() != null
                        ? new HashMap<>(workflow.getDefaultContext()) : new HashMap<>();
                Map<String, Object> repairMeta = Map.of("autoRepairAttempt", true);

                WorkflowRun run = workflowExecutor.execute(workflow, context, null, repairMeta);
                WorkflowRun completed = waitForRunCompletion(run.getId(), timeoutSeconds);

                if (completed != null && completed.getStatus() == RunStatus.COMPLETED) {
                    return new FactoryTestResult(completed.getId(), "PASSED", null);
                }

                // Extract error for next iteration
                if (completed != null && completed.getError() != null) {
                    lastError = extractErrorMessage(completed.getError());
                } else {
                    lastError = "Workflow failed without error details";
                }
            } catch (Exception e) {
                log.warn("Local repair: attempt {} failed: {}", i + 1, e.getMessage());
                lastError = e.getMessage();
            }
        }

        return new FactoryTestResult(null, "FAILED", lastError);
    }

    /**
     * Polls for workflow run completion with timeout.
     */
    private WorkflowRun waitForRunCompletion(String runId, long timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000;

        while (System.currentTimeMillis() < deadline) {
            WorkflowRun run = workflowRunRepository.findById(runId).orElse(null);
            if (run != null && run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PAUSED) {
                return run;
            }
            try {
                Thread.sleep(POLLING_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Local repair: polling interrupted for run {}", runId);
                return null;
            }
        }

        log.warn("Local repair: timeout waiting for run {} ({}s)", runId, timeoutSeconds);
        return null;
    }

    // ── Helpers ──────────────────────────────

    private boolean shouldUseTestInstance(String mode) {
        return switch (mode) {
            case "test" -> true;
            case "local" -> false;
            case "auto" -> testingService.isHealthy();
            default -> testingService.isHealthy();
        };
    }

    private String extractErrorMessage(Map<String, Object> error) {
        if (error == null || error.isEmpty()) {
            return "Unknown error";
        }
        Object message = error.get("message");
        if (message instanceof String s) {
            return s;
        }
        return error.toString();
    }

    private String extractLastFailedRunError(String workflowId) {
        return workflowRunRepository.findTopByWorkflowIdAndStatusOrderByStartedAtDesc(workflowId, RunStatus.FAILED)
                .map(run -> extractErrorMessage(run.getError()))
                .orElse("Unknown error (no failed run found)");
    }

    private void logRepairResult(FactoryTestResult result, String workflowName, String workflowId) {
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
