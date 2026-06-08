package com.automation.dto.response;

import com.automation.entity.RunStatus;

import java.time.Instant;

/**
 * Lean summary of a workflow whose most recent run failed — for the Workflow-Debug page
 * ("what is currently broken"). One entry per workflow (the latest run), deduplicated.
 *
 * <p>Deliberately omits the step input/output blobs: with pool-size=1 we never load
 * {@code StepResult} TEXT columns in aggregations (see dashboard lean-projection rationale).
 * The failed step index and block type come straight from the run's {@code error} map, which
 * {@code WorkflowExecutor} populates as {@code {"step", "blockType", "message"}} on block
 * failure — so no steps need to be loaded to render the overview. Full step detail is fetched
 * on demand via {@code GET /api/v1/executions/{id}}.
 */
public record BrokenWorkflowResponse(
        String workflowId,
        String workflowName,
        String projectName,
        String runId,
        RunStatus status,
        String errorMessage,
        Integer failedStepIndex,
        String failedBlockType,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        String triggeredByScheduleId
) {}
