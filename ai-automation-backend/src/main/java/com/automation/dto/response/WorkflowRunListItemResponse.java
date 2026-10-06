package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.StepStatus;
import com.automation.entity.TriggerSource;

import java.time.Instant;
import java.util.Map;

/**
 * A run row for lists - like {@link WorkflowRunResponse}, but without the steps.
 *
 * <p><b>Why a separate type.</b> The list route used the same DTO as the detail route and
 * thereby carried every {@code step_results.output} (TEXT) in the response: with 60 runs of three
 * 20 KB steps each <b>3 061 816 bytes</b>, measured by {@code RequestCostRatchetTest} on
 * 2026-09-18 (TODO a05c9504). In production it is larger - quality-gate outputs are in the high KB
 * to MB range, so with 50 runs per page that is double-digit MB per page request.
 *
 * <p>The same DTO with partly filled, partly empty steps would have been the quieter but
 * worse solution: then {@code steps} in the list means something different than in the
 * detail, and the next reader only finds out by trying. A separate type says it.
 *
 * <p>What the list needs from the last step is measured, not guessed: {@code Runs.tsx}
 * shows the <b>number</b> of steps and the output of the <b>last</b> step, truncated to
 * 300&nbsp;px with the full text in the tooltip. The other five consumers of the route do not touch the steps
 * at all.
 *
 * <p>There is no per-step error text: {@code Runs.tsx} reads {@code lastStep.error},
 * but {@link StepResultResponse} has no such field - the value was always {@code undefined}.
 * A run's error is in {@link #error}, and that is how it stays; inventing a field that
 * nothing fills would only have made the list opaque again.
 *
 * @param stepCount       number of steps - the list shows it but does not load them individually
 * @param lastStepStatus  status of the last step, {@code null} if there is none
 * @param lastStepPreview output of the last step, truncated to {@value #PREVIEW_LENGTH}
 *                        characters; the full text is in the detail route
 * @param sealed          seal state at start (true = sealed and verified, false = not
 *                        sealed, null = run from before the recording)
 * @param chainRunId      chain run that started the run as an entry; null outside a chain
 *                        and for runs from before V89 (like {@link WorkflowRunResponse#chainRunId()})
 */
public record WorkflowRunListItemResponse(
        String id,
        String workflowId,
        String workflowName,
        String projectName,
        String projectPath,
        RunStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Integer userRating,
        Map<String, Object> error,
        Map<String, Object> metadata,
        Map<String, Object> inputContext,
        String triggeredByScheduleId,
        String definitionHash,
        String definitionVersionId,
        TriggerSource triggerSource,
        int stepCount,
        StepStatus lastStepStatus,
        String lastStepPreview,
        Boolean sealed,
        String chainRunId
) {
    /** Compatibility constructor for callers from before the seal was recorded on the run. */
    public WorkflowRunListItemResponse(String id, String workflowId, String workflowName, String projectName,
                                       String projectPath, RunStatus status, Instant startedAt, Instant completedAt,
                                       Long durationMs, Integer userRating, Map<String, Object> error,
                                       Map<String, Object> metadata, Map<String, Object> inputContext,
                                       String triggeredByScheduleId, String definitionHash, String definitionVersionId,
                                       TriggerSource triggerSource, int stepCount, StepStatus lastStepStatus,
                                       String lastStepPreview) {
        this(id, workflowId, workflowName, projectName, projectPath, status, startedAt, completedAt, durationMs,
                userRating, error, metadata, inputContext, triggeredByScheduleId, definitionHash, definitionVersionId,
                triggerSource, stepCount, lastStepStatus, lastStepPreview, null, null);
    }

    /** Compatibility constructor for callers from before the chain reference in the list (TODO 6db47bc2). */
    public WorkflowRunListItemResponse(String id, String workflowId, String workflowName, String projectName,
                                       String projectPath, RunStatus status, Instant startedAt, Instant completedAt,
                                       Long durationMs, Integer userRating, Map<String, Object> error,
                                       Map<String, Object> metadata, Map<String, Object> inputContext,
                                       String triggeredByScheduleId, String definitionHash, String definitionVersionId,
                                       TriggerSource triggerSource, int stepCount, StepStatus lastStepStatus,
                                       String lastStepPreview, Boolean sealed) {
        this(id, workflowId, workflowName, projectName, projectPath, status, startedAt, completedAt, durationMs,
                userRating, error, metadata, inputContext, triggeredByScheduleId, definitionHash, definitionVersionId,
                triggerSource, stepCount, lastStepStatus, lastStepPreview, sealed, null);
    }

    /**
     * Length of the preview. 500 characters, because the list truncates the value to 300&nbsp;px anyway and
     * the tooltip thus still shows one sentence more than the row - more would again be the problem
     * this DTO solves.
     */
    public static final int PREVIEW_LENGTH = 500;
}
