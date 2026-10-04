package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Runs another workflow as one step; the run itself is {@link SubWorkflowLauncher}'s job. */
@Component
@RequiredArgsConstructor
public class SubWorkflowBlockExecutor implements BlockExecutor {

    private final SubWorkflowLauncher launcher;

    @Override
    public Set<String> supportedTypes() {
        return Set.of("sub-workflow");
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();

        String configured = (String) block.getConfig().get("workflow_id");
        if (configured == null || configured.isBlank()) {
            // Fallback: accept legacy camelCase key
            configured = (String) block.getConfig().get("workflowId");
        }
        if (configured == null || configured.isBlank()) {
            throw new WorkflowExecutionException("Sub-workflow block requires 'workflow_id' in config");
        }

        StringBuilder refusal = new StringBuilder();
        String workflowId = SubWorkflowLauncher.resolveWorkflowId(configured, context, refusal);
        SubWorkflowLauncher.Outcome outcome = workflowId != null
                ? launcher.run(workflowId, context)
                : SubWorkflowLauncher.Outcome.refused(configured, refusal.toString());
        Instant end = Instant.now();

        StepResult.StepResultBuilder result = StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Sub-Workflow] " + (outcome.workflowName() != null ? outcome.workflowName() : outcome.workflowId()))
                .output(outcome.output())
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .status(outcome.success() ? StepStatus.COMPLETED : StepStatus.FAILED);
        if (outcome.started()) {
            result.metadata(Map.of(
                    "subWorkflowId", outcome.workflowId(),
                    "subWorkflowName", outcome.workflowName(),
                    "subRunId", outcome.subRunId(),
                    "subRunStatus", outcome.subRunStatus().name(),
                    // The output is a copy of the child's; its gate_status/todo_item markers
                    // are evaluated in the child run only, never again here (6e5d6e2c).
                    "derivedOutput", true
            ));
        }
        return result.build();
    }
}
