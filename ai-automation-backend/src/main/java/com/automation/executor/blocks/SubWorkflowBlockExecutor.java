package com.automation.executor.blocks;

import com.automation.entity.*;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

@Component
@Slf4j
public class SubWorkflowBlockExecutor implements BlockExecutor {

    private static final Duration MAX_WAIT = Duration.ofMinutes(30);
    private static final int MAX_DEPTH = 5;

    private final WorkflowService workflowService;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRunRepository runRepository;
    private final StepResultRepository stepResultRepository;

    public SubWorkflowBlockExecutor(WorkflowService workflowService,
                                    @Lazy WorkflowExecutor workflowExecutor,
                                    WorkflowRunRepository runRepository,
                                    StepResultRepository stepResultRepository) {
        this.workflowService = workflowService;
        this.workflowExecutor = workflowExecutor;
        this.runRepository = runRepository;
        this.stepResultRepository = stepResultRepository;
    }

    @Override
    public boolean supports(String blockType) {
        return "sub-workflow".equals(blockType);
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();

        String workflowId = (String) block.getConfig().get("workflow_id");
        if (workflowId == null || workflowId.isBlank()) {
            // Fallback: accept legacy camelCase key
            workflowId = (String) block.getConfig().get("workflowId");
        }
        if (workflowId == null || workflowId.isBlank()) {
            throw new WorkflowExecutionException("Sub-workflow block requires 'workflow_id' in config");
        }

        // Check recursion depth
        @SuppressWarnings("unchecked")
        List<String> parentIds = (List<String>) context.getVariables()
                .getOrDefault("__parent_workflow_ids", new ArrayList<>());
        if (parentIds.size() >= MAX_DEPTH) {
            return StepResult.builder()
                    .stepIndex(stepIndex)
                    .input("[Sub-Workflow] " + workflowId)
                    .output("Maximum sub-workflow depth (" + MAX_DEPTH + ") exceeded")
                    .startedAt(start)
                    .completedAt(Instant.now())
                    .status(StepStatus.FAILED)
                    .build();
        }
        if (parentIds.contains(workflowId)) {
            return StepResult.builder()
                    .stepIndex(stepIndex)
                    .input("[Sub-Workflow] " + workflowId)
                    .output("Circular sub-workflow reference detected: " + workflowId)
                    .startedAt(start)
                    .completedAt(Instant.now())
                    .status(StepStatus.FAILED)
                    .build();
        }

        try {
            Workflow subWorkflow = workflowService.findById(workflowId);

            // Build child context with parent variables
            Map<String, Object> childContext = new HashMap<>(context.getVariables());
            childContext.put("previous_output", context.getPreviousOutput() != null ? context.getPreviousOutput() : "");
            List<String> newParentIds = new ArrayList<>(parentIds);
            newParentIds.add(workflowId);
            childContext.put("__parent_workflow_ids", newParentIds);
            // Taint travels into the child run: its shell blocks escape the same values
            childContext.putAll(context.exportTaint());

            // Execute sub-workflow
            WorkflowRun subRun = workflowExecutor.execute(subWorkflow, childContext, TriggerSource.SUB_WORKFLOW);

            // Poll for completion
            WorkflowRun completed = waitForCompletion(subRun.getId());
            Instant end = Instant.now();

            String output = buildOutput(completed);
            boolean success = completed.getStatus() == RunStatus.COMPLETED;

            return StepResult.builder()
                    .stepIndex(stepIndex)
                    .input("[Sub-Workflow] " + subWorkflow.getName())
                    .output(output)
                    .startedAt(start)
                    .completedAt(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .status(success ? StepStatus.COMPLETED : StepStatus.FAILED)
                    .metadata(Map.of(
                            "subWorkflowId", workflowId,
                            "subWorkflowName", subWorkflow.getName(),
                            "subRunId", completed.getId(),
                            "subRunStatus", completed.getStatus().name()
                    ))
                    .build();

        } catch (Exception e) {
            log.error("Sub-workflow {} execution failed: {}", workflowId, e.getMessage(), e);
            Instant end = Instant.now();
            return StepResult.builder()
                    .stepIndex(stepIndex)
                    .input("[Sub-Workflow] " + workflowId)
                    .output("Sub-workflow execution failed: " + e.getMessage())
                    .startedAt(start)
                    .completedAt(end)
                    .durationMs(end.toEpochMilli() - start.toEpochMilli())
                    .status(StepStatus.FAILED)
                    .build();
        }
    }

    private WorkflowRun waitForCompletion(String runId) {
        Instant deadline = Instant.now().plus(MAX_WAIT);
        while (Instant.now().isBefore(deadline)) {
            WorkflowRun run = runRepository.findById(runId).orElseThrow(() ->
                    new WorkflowExecutionException("Sub-workflow run " + runId + " disappeared while waiting"));
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return run;
            }
            try {
                Thread.sleep(2000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new WorkflowExecutionException("Interrupted while waiting for sub-workflow");
            }
        }
        throw new WorkflowExecutionException("Sub-workflow timed out after " + MAX_WAIT.toMinutes() + " minutes");
    }

    private String buildOutput(WorkflowRun run) {
        List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(run.getId());
        StringBuilder sb = new StringBuilder();
        sb.append("Sub-workflow completed with status: ").append(run.getStatus()).append("\n");
        for (StepResult step : steps) {
            sb.append("[Step ").append(step.getStepIndex()).append("] ")
                    .append(step.getStatus())
                    .append(": ").append(truncate(step.getOutput(), 500)).append("\n");
        }
        // Return last step output as main output
        if (!steps.isEmpty()) {
            StepResult lastStep = steps.get(steps.size() - 1);
            if (lastStep.getOutput() != null) {
                return lastStep.getOutput();
            }
        }
        return sb.toString();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
