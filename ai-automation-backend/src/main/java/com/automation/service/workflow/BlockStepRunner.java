package com.automation.service.workflow;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.WorkflowRun;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.repository.ContainerRepository;
import com.automation.repository.StepResultRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.live.LiveOutput;
import com.automation.util.ProcessTracking;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Runs a single block of a workflow: the RUNNING placeholder the UI shows immediately, the actual
 * execution including output validation and retries, and copying the result into the step, the
 * execution context and the database.
 *
 * <p>The order inside {@link #runStep} is behavior: execute → retry loop → final check (all with the
 * step's live-log sink bound, see {@link LiveOutput}) → flush live lines → step fields → context
 * variables → context delta → container activity → broadcast → persist. The
 * decision whether a failed step stops the run stays in {@code WorkflowExecutor}, because it
 * controls the block loop.
 *
 * <p>Extracted 1:1 from {@code WorkflowExecutor} (clean-code block 3c).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class BlockStepRunner {

    private final StepResultRepository stepResultRepository;
    private final ExecutionBroadcaster broadcaster;
    private final BlockOutputValidator blockOutputValidator;
    private final ActivityLogService activityLogService;
    private final ContainerRepository containerRepository;
    private final RunLiveLogService liveLog;

    /** Pre-creates, persists and broadcasts the RUNNING placeholder so the UI shows the step immediately. */
    public StepResult startStep(WorkflowRun run, int stepIndex, BlockDefinition block) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());
        step.setStatus(StepStatus.RUNNING);
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", block.getType());
        if (block.getName() != null && !block.getName().isBlank()) {
            metadata.put("blockName", block.getName());
        }
        step.setMetadata(metadata);
        step.setWorkflowRun(run);
        run.getSteps().add(step);
        stepResultRepository.save(step);
        broadcaster.broadcastStepResult(run.getId(), step);
        return step;
    }

    /** Executes the block (with validation + retry) and copies the result into {@code step} and the context. */
    public void runStep(StepResult step, BlockDefinition block, BlockExecutor executor, ExecutionContext context,
                        WorkflowRun run, String containerId) {
        // Execute the block — with optional output validation + retry. Output of the processes it
        // starts streams into the live log under this step's index, nested blocks included, and
        // those processes are registered with the run so a cancel kills them.
        StepResult result;
        try {
            result = LiveOutput.bind(liveLog.sinkFor(run.getId(), step.getStepIndex(), block.getName()),
                    () -> ProcessTracking.bind(context.processTracker(),
                            () -> executeValidated(step, block, executor, context, run)));
        } finally {
            liveLog.flush(run.getId()); // live lines must reach the client before the final step message
        }

        // Update the persisted step with the actual results
        step.setInput(result.getInput());
        step.setOutput(result.getOutput());
        step.setStatus(result.getStatus());
        step.setCompletedAt(result.getCompletedAt());
        step.setDurationMs(result.getDurationMs());
        Map<String, Object> stepMetadata = result.getMetadata() != null
                ? new HashMap<>(result.getMetadata())
                : new HashMap<>();
        stepMetadata.put("blockType", block.getType());
        if (block.getName() != null && !block.getName().isBlank()) {
            stepMetadata.put("blockName", block.getName());
        }
        step.setMetadata(stepMetadata);
        // recordOutput/setDerivedVariable, not the plain setters: in a run that carries untrusted
        // input (webhook payload) the output is tainted and gets shell-escaped downstream.
        context.recordOutput(block.getName(), step.getOutput());
        context.setDerivedVariable("step_" + step.getStepIndex() + "_output",
                step.getOutput() != null ? step.getOutput() : "");

        // Persist context delta for potential future resume
        if (step.getStatus() == StepStatus.COMPLETED) {
            step.setContextDelta(ContextDeltaCalculator.compute(context, run.getInputContext()));
        }

        // Update container last activity (skip if no container)
        if (containerId != null) {
            updateContainerActivity(containerId);
        }

        broadcaster.broadcastStepResult(run.getId(), step);
        stepResultRepository.save(step);
    }

    /** Runs the block, re-running it while output validation fails and retries are configured. */
    private StepResult executeValidated(StepResult step, BlockDefinition block, BlockExecutor executor,
                                        ExecutionContext context, WorkflowRun run) {
        StepResult result = executor.execute(block, context);
        int validationAttempts = 0;
        int maxValidationAttempts = blockOutputValidator.maxRetries(block);
        while (result.getStatus() == StepStatus.COMPLETED && validationAttempts < maxValidationAttempts) {
            var validation = blockOutputValidator.validate(block, result.getOutput());
            if (validation.passed()) break;
            if (!blockOutputValidator.shouldRetry(block)) {
                log.warn("Block {} output validation failed without retry: {}", step.getStepIndex(), validation.reason());
                result.setStatus(StepStatus.FAILED);
                String existing = result.getOutput() == null ? "" : result.getOutput();
                result.setOutput(existing + "\n[VALIDATION] " + validation.reason());
                break;
            }
            validationAttempts++;
            log.info("Block {} output validation failed (attempt {}/{}): {}",
                    step.getStepIndex(), validationAttempts, maxValidationAttempts, validation.reason());
            activityLogService.warn(LogCategory.EXECUTION,
                    "Block " + step.getStepIndex() + " validation failed (attempt " + validationAttempts + "/" + maxValidationAttempts + "): " + validation.reason(),
                    "WorkflowExecutor", run.getId());
            result = executor.execute(block, context);
        }
        if (result.getStatus() == StepStatus.COMPLETED) {
            var finalCheck = blockOutputValidator.validate(block, result.getOutput());
            if (!finalCheck.passed()) {
                log.warn("Block {} output validation failed after {} retries: {}",
                        step.getStepIndex(), validationAttempts, finalCheck.reason());
                result.setStatus(StepStatus.FAILED);
                String existing = result.getOutput() == null ? "" : result.getOutput();
                result.setOutput(existing + "\n[VALIDATION] failed after " + validationAttempts + " retries: " + finalCheck.reason());
            }
        }
        return result;
    }

    // One JPQL UPDATE: find + save would detach, merge and drag the eager Project/Container graph.
    void updateContainerActivity(String containerId) {
        try {
            containerRepository.touchActivity(containerId, Instant.now());
        } catch (Exception e) {
            log.debug("Failed to update container activity: {}", e.getMessage());
        }
    }
}
