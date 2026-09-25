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
import java.util.Set;

/**
 * Runs a single block of a workflow: the RUNNING placeholder the UI shows immediately, the actual
 * execution including output validation and retries, and copying the result into the step, the
 * execution context and the database.
 *
 * <p>The order inside {@link #runStep} is behavior: run_if ({@link RunIfGate}, TODO cc0eb3ba) → execute → retry loop → final check (all with the
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
    private final RunIfGate runIfGate;

    /**
     * Pre-creates, persists and broadcasts the RUNNING placeholder so the UI shows the step immediately.
     * {@code blockPosition} is the block's place in the top-level block list, stored as metadata
     * {@code blockIndex}: the step index runs ahead of it once a wrapper block has run (see
     * {@link com.automation.executor.StepIndexCounter}), so only this key maps a step to its block.
     */
    public StepResult startStep(WorkflowRun run, int stepIndex, BlockDefinition block, int blockPosition) {
        StepResult step = new StepResult();
        step.setStepIndex(stepIndex);
        step.setStartedAt(Instant.now());
        step.setStatus(StepStatus.RUNNING);
        Map<String, Object> metadata = new HashMap<>();
        labelBlock(metadata, block);
        metadata.put("blockIndex", blockPosition);
        step.setMetadata(metadata);
        step.setWorkflowRun(run);
        run.getSteps().add(step);
        stepResultRepository.save(step);
        broadcaster.broadcastStepResult(run.getId(), step);
        return step;
    }

    /** Block types whose {@code config.description} is content (the TODO text), not a note on the step. */
    private static final Set<String> DESCRIPTION_IS_CONTENT = Set.of("todo-create", "todo-update");

    /**
     * What the run view shows about the step's block: type, name and the step note
     * {@code config.description} as {@code blockDescription} (TODO 31b7aca8 — blueprints explain
     * each step there). Written at start and again after the run, which replaces the metadata.
     */
    static void labelBlock(Map<String, Object> metadata, BlockDefinition block) {
        metadata.put("blockType", block.getType());
        if (block.getName() != null && !block.getName().isBlank()) {
            metadata.put("blockName", block.getName());
        }
        Object note = block.getConfig() != null ? block.getConfig().get("description") : null;
        if (note instanceof String text && !text.isBlank() && !DESCRIPTION_IS_CONTENT.contains(block.getType())) {
            metadata.put("blockDescription", text);
        }
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
                            () -> runIfGate.skipIfUnmet(block, context)
                                    .orElseGet(() -> executeValidated(step, block, executor, context, run))));
        } finally {
            liveLog.flush(run.getId()); // live lines must reach the client before the final step message
        }

        // Update the persisted step with the actual results
        step.setInput(result.getInput());
        step.setOutput(result.getOutput());
        step.setStatus(result.getStatus());
        step.setCompletedAt(result.getCompletedAt());
        step.setDurationMs(result.getDurationMs());
        Object blockIndex = step.getMetadata() != null ? step.getMetadata().get("blockIndex") : null;
        Map<String, Object> stepMetadata = result.getMetadata() != null
                ? new HashMap<>(result.getMetadata())
                : new HashMap<>();
        labelBlock(stepMetadata, block);
        if (blockIndex != null) {
            stepMetadata.put("blockIndex", blockIndex);
        }
        step.setMetadata(stepMetadata);
        // recordOutput/setDerivedVariable, not the plain setters: in a run that carries untrusted
        // input (webhook payload) the output is tainted and gets shell-escaped downstream.
        context.recordOutput(block.getName(), step.getOutput());
        context.setDerivedVariable("step_" + step.getStepIndex() + "_output",
                step.getOutput() != null ? step.getOutput() : "");
        context.getSourceSteps().record(block.getName(), step);

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
