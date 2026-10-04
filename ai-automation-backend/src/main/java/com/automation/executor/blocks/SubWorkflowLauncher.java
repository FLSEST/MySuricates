package com.automation.executor.blocks;

import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.TriggerSource;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.executor.PlaceholderSyntax;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.RunControlService;
import com.automation.service.workflow.WorkflowContextDataService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Starts one child run of another workflow and waits for it — the mechanism behind the
 * {@code sub-workflow} block and {@code foreach} with {@code workflow_id} (TODO a5c9fbbf). One place
 * for the guards both share: depth limit, cycle check, the parent's variables without its platform
 * data, the parent's taint and effective origin, admission as {@link TriggerSource#SUB_WORKFLOW}.
 */
@Component
@Slf4j
public class SubWorkflowLauncher {

    private static final Duration MAX_WAIT = Duration.ofMinutes(30);
    static final int MAX_DEPTH = 5;

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;
    private final WorkflowContextDataService contextDataService;
    private final RunControlService runControl;
    /** How often the parent looks at its child; package-private so tests need not wait two seconds. */
    Duration pollInterval = Duration.ofSeconds(2);

    public SubWorkflowLauncher(WorkflowService workflowService,
                               @Lazy WorkflowExecutor workflowExecutor,
                               WorkflowRunRepository runRepository,
                               StepResultRepository stepResultRepository,
                               WorkflowContextDataService contextDataService,
                               @Lazy RunControlService runControl) {
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.runRepository = runRepository;
        this.stepResultRepository = stepResultRepository;
        this.contextDataService = contextDataService;
        this.runControl = runControl;
    }

    /**
     * What one child run produced. {@code workflowName} and {@code subRunId} are null when the run never
     * started (guard, unknown workflow); {@code output} then carries the reason.
     */
    public record Outcome(String workflowId, String workflowName, String subRunId, RunStatus subRunStatus,
                          String output, boolean success) {

        static Outcome refused(String workflowId, String reason) {
            return new Outcome(workflowId, null, null, null, reason, false);
        }

        /** True once a child run exists — its output is a copy of the child's, see {@code derivedOutput}. */
        public boolean started() {
            return subRunId != null;
        }
    }

    /**
     * Resolves {@code {{ … }}} in a configured workflow id against the parent's variables. Only trusted
     * variables may choose the child: an id taken from a webhook payload or a block output would let
     * outside input pick any workflow of the instance. Returns null with the reason in {@code refusal}.
     */
    public static String resolveWorkflowId(String configured, ExecutionContext context, StringBuilder refusal) {
        for (String key : PlaceholderSyntax.keysIn(configured)) {
            if ("previous_output".equals(key) || key.startsWith("block.") || context.isUntrusted(key)) {
                refusal.append("workflow_id may only use trusted context variables, not {{ ").append(key).append(" }}");
                return null;
            }
        }
        String resolved = context.resolveTemplate(configured).strip();
        if (resolved.isEmpty() || !PlaceholderSyntax.keysIn(resolved).isEmpty()) {
            refusal.append("workflow_id '").append(configured).append("' does not resolve to a workflow id");
            return null;
        }
        return resolved;
    }

    /**
     * Runs workflow {@code workflowId} (already resolved) as a child of {@code context} and waits for
     * it. Never throws: every failure — guard, unknown workflow, refused admission, timeout — comes
     * back as an unsuccessful {@link Outcome}.
     */
    public Outcome run(String workflowId, ExecutionContext context) {
        @SuppressWarnings("unchecked")
        List<String> parentIds = (List<String>) context.getVariables()
                .getOrDefault("__parent_workflow_ids", new ArrayList<>());
        if (parentIds.size() >= MAX_DEPTH) {
            return Outcome.refused(workflowId, "Maximum sub-workflow depth (" + MAX_DEPTH + ") exceeded");
        }
        if (parentIds.contains(workflowId)) {
            return Outcome.refused(workflowId, "Circular sub-workflow reference detected: " + workflowId);
        }
        String name = null;
        try {
            Workflow subWorkflow = workflowService.findById(workflowId);
            name = subWorkflow.getName();
            WorkflowRun subRun = workflowExecutor.execute(subWorkflow, childContext(context, parentIds, workflowId),
                    TriggerSource.SUB_WORKFLOW);
            WorkflowRun completed = waitForCompletion(subRun.getId(), context);
            return new Outcome(workflowId, name, completed.getId(), completed.getStatus(), buildOutput(completed),
                    completed.getStatus() == RunStatus.COMPLETED);
        } catch (Exception e) {
            log.error("Sub-workflow {} execution failed: {}", workflowId, e.getMessage(), e);
            return new Outcome(workflowId, name, null, null, "Sub-workflow execution failed: " + e.getMessage(), false);
        }
    }

    /**
     * The parent's variables — without its platform data: the child gets its own injected, and the copy
     * only bloated the child's stored input_context (open_todos alone carries up to 150 titles). Its
     * taint still travels (TODO 2a0695ff).
     */
    private Map<String, Object> childContext(ExecutionContext context, List<String> parentIds, String workflowId) {
        Map<String, Object> childContext = new HashMap<>(context.getVariables());
        childContext.keySet().removeAll(contextDataService.platformVariableNames());
        childContext.put("previous_output", context.getPreviousOutput() != null ? context.getPreviousOutput() : "");
        List<String> newParentIds = new ArrayList<>(parentIds);
        newParentIds.add(workflowId);
        childContext.put("__parent_workflow_ids", newParentIds);
        TriggerSource origin = TriggerSource.effectiveOfRun(context.getVariables());
        if (origin != null) {
            childContext.put(TriggerSource.PARENT_CONTEXT_KEY, origin.name());
        } else {
            childContext.remove(TriggerSource.PARENT_CONTEXT_KEY); // unknown origin counts as unattended
        }
        // Taint travels into the child run: its shell blocks escape the same values
        childContext.putAll(context.exportTaint());
        return childContext;
    }

    /**
     * Polls the child until it ends. A cancelled parent cancels the child once and keeps waiting for its
     * final status — otherwise the child ran on and the parent's thread waited up to {@link #MAX_WAIT}.
     */
    private WorkflowRun waitForCompletion(String runId, ExecutionContext parent) {
        Instant deadline = Instant.now().plus(MAX_WAIT);
        boolean childCancelled = false;
        while (Instant.now().isBefore(deadline)) {
            if (!childCancelled && parent.shouldStop()) {
                log.info("Parent run {} cancelled — cancelling sub-workflow run {}", parent.getWorkflowRunId(), runId);
                runControl.cancelExecution(runId);
                childCancelled = true;
            }
            WorkflowRun run = runRepository.findById(runId).orElseThrow(() ->
                    new WorkflowExecutionException("Sub-workflow run " + runId + " disappeared while waiting"));
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return run;
            }
            try {
                Thread.sleep(pollInterval.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowExecutionException("Interrupted while waiting for sub-workflow");
            }
        }
        throw new WorkflowExecutionException("Sub-workflow timed out after " + MAX_WAIT.toMinutes() + " minutes");
    }

    /** The child's last step output; without one, a status line per step. */
    private String buildOutput(WorkflowRun run) {
        List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
        if (!steps.isEmpty() && steps.get(steps.size() - 1).getOutput() != null) {
            return steps.get(steps.size() - 1).getOutput();
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Sub-workflow completed with status: ").append(run.getStatus()).append("\n");
        for (StepResult step : steps) {
            sb.append("[Step ").append(step.getStepIndex()).append("] ")
                    .append(step.getStatus())
                    .append(": ").append(truncate(step.getOutput(), 500)).append("\n");
        }
        return sb.toString();
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
