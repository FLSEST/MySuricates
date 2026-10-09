package com.automation.service.workflow;

import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dashboard.DashboardWidgetResultParser;
import com.automation.service.runoutput.GateExpectation;
import com.automation.service.runoutput.TodoOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Post-processes a finished block loop: parses gate/todo markers from all step outputs into
 * the context's run metadata, finalizes RUNNING -> COMPLETED, records template verification,
 * writes the activity-log entry and the dead-letter entry for FAILED runs.
 *
 * <p>Does <b>not</b> persist the run — the final save stays in {@code WorkflowExecutor}'s
 * finally block. Extracted from {@code WorkflowExecutor} (review C1, roadmap 3.3).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class RunCompletionHandler {

    /** Category and text for an ordered but missing gate verdict (TODO 63ecc124). */
    public static final String GATE_MISSING_CATEGORY = "gate-missing";
    static final String GATE_MISSING_DESCRIPTION =
            "Die Definition bestellt ein Gate-Urteil, der Lauf hat keines geliefert";

    private final TemplateVerificationService templateVerificationService;
    private final ActivityLogService activityLogService;

    public void complete(WorkflowRun run, ExecutionContext context, String workflowId,
                         String workflowName, String templateId, String projectId,
                         List<BlockDefinition> blocks) {
        List<StepResult> markerSteps = run.getSteps().stream()
                .filter(step -> step.getOutput() != null && !isDerivedOutput(step))
                .toList();
        GateAggregation gate = GateAggregation.of(markerSteps);
        if (gate != null) {
            gate.writeTo(context);
        }
        collectTodoItems(markerSteps, context, workflowName);
        applyDashboardWidget(markerSteps, context, workflowName);

        boolean failedChecks = hasFailedCheckedCommand(run);
        resolveFinalStatus(run, failedChecks);
        boolean runSucceeded = run.getStatus() == RunStatus.COMPLETED
                || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS;

        // An ordered but missing verdict is not a success (TODO 63ecc124): a check workflow
        // whose AI step fails silently runs through technically and could otherwise not be told apart
        // from a workflow without a gate. Only for a successful run - a FAILED one already carries
        // its own verdict via the run status.
        if (gate == null && runSucceeded && GateExpectation.isExpected(blocks)) {
            gate = GateAggregation.missing();
            gate.writeTo(context);
            log.warn("Workflow {} orders a gate verdict but delivered none (run {})", workflowName, run.getId());
        }

        if (runSucceeded && !failedChecks) {
            recordTemplateVerification(workflowId, templateId, projectId, run.getId());
        }
        logCompletion(run, gate != null ? gate.verdict().status() : null, runSucceeded, workflowId);
    }

    /** Parse todo_item markers from ALL blocks — collect granular TODO items. */
    private static void collectTodoItems(List<StepResult> markerSteps, ExecutionContext context, String workflowName) {
        List<TodoOutputParser.ParsedTodoItem> items = markerSteps.stream()
                .flatMap(step -> TodoOutputParser.parse(step.getOutput()).stream())
                .toList();
        if (items.isEmpty()) {
            return;
        }
        context.setRunMetadata("todo_items", items.stream()
                .map(t -> {
                    Map<String, Object> m = new HashMap<>();
                    m.put("title", t.title());
                    m.put("priority", t.priority());
                    m.put("tags", t.tags());
                    if (t.description() != null) m.put("description", t.description());
                    return m;
                })
                .toList());
        log.info("Parsed {} todo_item markers from workflow {}", items.size(), workflowName);
    }

    /**
     * dashboard_widget marker: what a workflow result widget shows (spec E3). Parsed here, where
     * the steps are already in memory — the read path must never load step output blobs (pool=1).
     * Derived outputs are skipped: a compression step copies its source's text.
     *
     * <p>A dashboard-report block (TODO f4231dc9) has already written a validated contract into the
     * run metadata. It wins, and the marker is not even looked at: the block is the deliberately
     * configured path, while a marker line in a later AI output could otherwise overwrite the
     * report a human configured — prompt injection with visible effect on the dashboard.
     */
    private static void applyDashboardWidget(List<StepResult> markerSteps, ExecutionContext context,
                                             String workflowName) {
        if (context.getRunMetadata().containsKey(DashboardWidgetResultParser.METADATA_KEY)) {
            return;
        }
        DashboardWidgetResultParser.ParseResult widgetResult = DashboardWidgetResultParser.parseRun(
                markerSteps.stream().map(StepResult::getOutput).toList());
        if (widgetResult.state() == ResultState.VALID) {
            context.setRunMetadata(DashboardWidgetResultParser.METADATA_KEY,
                    DashboardWidgetResultParser.toMetadata(widgetResult.result()));
        } else if (widgetResult.state() == ResultState.INVALID) {
            // A fixed token, never anything from the output: the widget says "invalid" instead of nothing.
            context.setRunMetadata(DashboardWidgetResultParser.ERROR_METADATA_KEY, "INVALID");
            log.info("Workflow {} reported a dashboard_widget marker that does not match the contract", workflowName);
        }
    }

    private static boolean hasFailedCheckedCommand(WorkflowRun run) {
        return run.getSteps().stream().anyMatch(step ->
                step.getStatus() == StepStatus.FAILED && step.getMetadata() != null
                        && Boolean.TRUE.equals(step.getMetadata().get("checkedCommand")));
    }

    /** A run still RUNNING after the block loop is done: with a failed checked_command it completed with errors. */
    private static void resolveFinalStatus(WorkflowRun run, boolean failedChecks) {
        if (run.getStatus() == RunStatus.RUNNING) {
            run.setStatus(failedChecks ? RunStatus.COMPLETED_WITH_ERRORS : RunStatus.COMPLETED);
        }
    }

    /** Record template verification if workflow was created from a template. */
    private void recordTemplateVerification(String workflowId, String templateId, String projectId, String runId) {
        if (templateId == null || templateId.isBlank()) {
            return;
        }
        try {
            templateVerificationService.recordSuccessfulRun(workflowId, templateId, projectId, runId);
        } catch (Exception e) {
            log.warn("Failed to record template verification: {}", e.getMessage());
        }
    }

    /** One activity-log entry per succeeded or FAILED run; a gate worse than GREEN makes it a warning. */
    private void logCompletion(WorkflowRun run, GateStatus gate, boolean runSucceeded, String workflowId) {
        if (run.getStatus() == RunStatus.FAILED) {
            // DeadLetterService creates the DLQ entry via WorkflowRunCompletedEvent - so
            // service.workflow does not depend on service.dlq (package cycle, TODO d55c55de).
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution failed: " + run.getId(),
                    "WorkflowExecutor", run.getId(), workflowId);
            return;
        }
        if (!runSucceeded) {
            return;
        }
        if (gate == GateStatus.RED) {
            activityLogService.warn(LogCategory.EXECUTION,
                    "Workflow completed with critical findings: " + run.getId(),
                    "WorkflowExecutor", run.getId(), workflowId);
        } else if (gate == GateStatus.YELLOW) {
            activityLogService.warn(LogCategory.EXECUTION,
                    "Workflow completed with warnings: " + run.getId(),
                    "WorkflowExecutor", run.getId(), workflowId);
        } else {
            activityLogService.info(LogCategory.EXECUTION,
                    "Workflow execution completed: " + run.getId(),
                    "WorkflowExecutor", run.getId(), workflowId);
        }
    }

    /** Compression steps copy their source's text; markers stay authoritative only on the original step (6e5d6e2c). */
    private static boolean isDerivedOutput(StepResult step) {
        return step.getMetadata() != null && Boolean.TRUE.equals(step.getMetadata().get("derivedOutput"));
    }
}
