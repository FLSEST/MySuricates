package com.automation.service.workflow;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Loads platform data that is injected into the {@link ExecutionContext} as template
 * variables ({@code previous_report}, {@code open_todos}) so workflows never need direct
 * DB or API access. Extracted from {@code WorkflowExecutor} (pure data queries).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowContextDataService {

    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;
    private final TodoRepository todoRepository;

    /**
     * Injects platform data into the execution context so workflows don't need
     * direct DB or API access. Available as {{ variable_name }} in block templates.
     *
     * <p>The platform API key is intentionally <b>not</b> injected as a template
     * variable. Doing so would resolve the literal key into block prompts, then
     * into LLM output, and finally persist it in {@link com.automation.entity.StepResult#getOutput()}
     * (SQLite, backups, UI). Instead, the key is set as the {@code AUTOMATION_API_KEY}
     * environment variable on the CLI process spawned by
     * {@link com.automation.service.ai.HostClaudeService} and
     * {@link com.automation.service.ai.HostCodexService}. Templates that need it
     * use {@code curl -H "X-API-Key: $AUTOMATION_API_KEY"} from the agent's Bash
     * tool — the resolved value never enters the prompt or persisted output.
     */
    public void injectPlatformData(ExecutionContext context, String workflowId, String projectId) {
        try {
            context.setVariable("previous_report", loadPreviousReport(workflowId));
            context.setVariable("open_todos", loadOpenTodos(projectId));
        } catch (Exception e) {
            log.warn("Failed to inject platform data: {}", e.getMessage());
        }
    }

    /**
     * Loads the analysis report from the last completed run of the same workflow.
     * Returns the longest non-shell step output (the Opus analysis), truncated to 3000 chars.
     */
    public String loadPreviousReport(String workflowId) {
        try {
            List<WorkflowRun> previousRuns = runRepository.findTop5ByWorkflowIdOrderByStartedAtDesc(workflowId);
            for (WorkflowRun prev : previousRuns) {
                if (prev.getStatus() != RunStatus.COMPLETED && prev.getStatus() != RunStatus.COMPLETED_WITH_ERRORS) {
                    continue;
                }
                List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(prev.getId());
                // Find the longest completed non-first step output (skip shell data-collection block)
                String bestOutput = null;
                int bestLength = 0;
                for (int i = 1; i < steps.size(); i++) {
                    StepResult step = steps.get(i);
                    if (step.getStatus() != StepStatus.COMPLETED) continue;
                    String output = step.getOutput();
                    if (output != null && output.length() > bestLength) {
                        bestLength = output.length();
                        bestOutput = output;
                    }
                }
                if (bestOutput != null && bestLength > 200) {
                    return bestLength > 3000 ? bestOutput.substring(0, 3000) + "\n[...gekürzt]" : bestOutput;
                }
            }
        } catch (Exception e) {
            log.debug("Could not load previous report for workflow {}: {}", workflowId, e.getMessage());
        }
        return "(kein vorheriger Report vorhanden)";
    }

    /**
     * Loads open TODOs for the project as a markdown list.
     */
    public String loadOpenTodos(String projectId) {
        try {
            List<com.automation.entity.Todo> todos = todoRepository.findByProjectIdAndStatusIn(
                    projectId, List.of(com.automation.entity.Todo.Status.OPEN, com.automation.entity.Todo.Status.IN_PROGRESS));
            if (todos.isEmpty()) return "(keine offenen TODOs)";
            StringBuilder sb = new StringBuilder();
            for (com.automation.entity.Todo t : todos) {
                String id = t.getId() != null ? t.getId() : "";
                String tags = t.getTags() != null ? t.getTags().trim() : "";
                sb.append("- ").append(id).append(" [").append(t.getPriority()).append("]");
                if (!tags.isEmpty()) {
                    sb.append(" [").append(tags).append("]");
                }
                sb.append(" ").append(t.getTitle()).append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            log.debug("Could not load open TODOs for project {}: {}", projectId, e.getMessage());
        }
        return "(TODOs nicht verfügbar)";
    }
}
