package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dlq.DeadLetterService;
import com.automation.service.todo.GateStatusOutputParser;
import com.automation.service.todo.TodoOutputParser;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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

    private final TemplateVerificationService templateVerificationService;
    private final ActivityLogService activityLogService;
    private final DeadLetterService deadLetterService;

    public void complete(WorkflowRun run, ExecutionContext context, String workflowId,
                         String workflowName, String templateId, String projectId) {
        // Parse gate_status markers from ALL blocks — highest severity wins.
        // GateStatus ordinal: GREEN(0) < YELLOW(1) < RED(2)
        GateStatus highestGate = null;
        String gateCategory = null;
        String gateDescription = null;
        // Aggregate every parsed gate so trend tooling (e.g. public-readiness chains) can
        // diff runs without re-parsing step outputs.
        List<Map<String, Object>> gateItems = new ArrayList<>();
        Map<String, Integer> gateCountsByStatus = new HashMap<>();
        for (StepResult step : run.getSteps()) {
            if (step.getOutput() != null) {
                var parsedGate = GateStatusOutputParser.parse(step.getOutput());
                if (parsedGate.isPresent()) {
                    var gate = parsedGate.get();
                    if (highestGate == null || gate.status().ordinal() > highestGate.ordinal()) {
                        highestGate = gate.status();
                        gateCategory = gate.category();
                        gateDescription = gate.description();
                    }
                    Map<String, Object> item = new HashMap<>();
                    item.put("status", gate.status().name());
                    if (gate.category() != null) item.put("category", gate.category());
                    if (gate.description() != null) item.put("description", gate.description());
                    item.put("step_index", step.getStepIndex());
                    gateItems.add(item);
                    gateCountsByStatus.merge(gate.status().name(), 1, Integer::sum);
                }
            }
        }
        if (highestGate != null) {
            context.setRunMetadata("gate_status", highestGate.name());
            if (gateCategory != null) context.setRunMetadata("gate_category", gateCategory);
            if (gateDescription != null) context.setRunMetadata("gate_description", gateDescription);

            // gate_summary aggregates ALL parsed gates so a follow-up run can be diffed
            // against a previous one without re-walking step outputs.
            Map<String, Object> gateSummary = new HashMap<>();
            gateSummary.put("highest", highestGate.name());
            gateSummary.put("counts_by_status", gateCountsByStatus);
            gateSummary.put("items", gateItems);
            context.setRunMetadata("gate_summary", gateSummary);
        }

        // Parse todo_item markers from ALL blocks — collect granular TODO items
        List<TodoOutputParser.ParsedTodoItem> allTodoItems = new ArrayList<>();
        for (StepResult step : run.getSteps()) {
            if (step.getOutput() != null) {
                allTodoItems.addAll(TodoOutputParser.parse(step.getOutput()));
            }
        }
        if (!allTodoItems.isEmpty()) {
            context.setRunMetadata("todo_items", allTodoItems.stream()
                    .map(t -> {
                        Map<String, Object> m = new HashMap<>();
                        m.put("title", t.title());
                        m.put("priority", t.priority());
                        m.put("tags", t.tags());
                        if (t.description() != null) m.put("description", t.description());
                        return m;
                    })
                    .toList());
            log.info("Parsed {} todo_item markers from workflow {}", allTodoItems.size(), workflowName);
        }

        boolean failedChecks = run.getSteps().stream().anyMatch(step ->
                step.getStatus() == com.automation.entity.StepStatus.FAILED && step.getMetadata() != null
                        && Boolean.TRUE.equals(step.getMetadata().get("checkedCommand")));
        if (run.getStatus() == RunStatus.RUNNING) {
            run.setStatus(failedChecks ? RunStatus.COMPLETED_WITH_ERRORS : RunStatus.COMPLETED);
        }

        if (run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS) {
            // Record template verification if workflow was created from a template
            if (!failedChecks && templateId != null && !templateId.isBlank()) {
                try {
                    templateVerificationService.recordSuccessfulRun(workflowId, templateId, projectId, run.getId());
                } catch (Exception e) {
                    log.warn("Failed to record template verification: {}", e.getMessage());
                }
            }
            if (highestGate == GateStatus.RED) {
                activityLogService.warn(LogCategory.EXECUTION,
                        "Workflow completed with critical findings: " + run.getId(),
                        "WorkflowExecutor", run.getId(), workflowId);
            } else if (highestGate == GateStatus.YELLOW) {
                activityLogService.warn(LogCategory.EXECUTION,
                        "Workflow completed with warnings: " + run.getId(),
                        "WorkflowExecutor", run.getId(), workflowId);
            } else {
                activityLogService.info(LogCategory.EXECUTION,
                        "Workflow execution completed: " + run.getId(),
                        "WorkflowExecutor", run.getId(), workflowId);
            }
        } else if (run.getStatus() == RunStatus.FAILED) {
            activityLogService.error(LogCategory.EXECUTION,
                    "Workflow execution failed: " + run.getId(),
                    "WorkflowExecutor", run.getId(), workflowId);
            try {
                deadLetterService.recordFailure(run);
            } catch (Exception dlqError) {
                log.warn("Failed to record DLQ entry for run {}: {}", run.getId(), dlqError.getMessage());
            }
        }
    }
}
