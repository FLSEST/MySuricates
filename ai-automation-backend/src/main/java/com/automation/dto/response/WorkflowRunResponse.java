package com.automation.dto.response;

import com.automation.entity.RunStatus;
import com.automation.entity.TriggerSource;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param definitionHash      SHA-256 of the workflow definition this run executed (null for old runs
 *                            or with {@code automation.hardening.provenance=false})
 * @param definitionVersionId version row matching {@code definitionHash}, if one exists
 * @param triggerSource       what started the run (null for runs created before provenance)
 * @param sealed              seal state recorded at start: true = sealed and verified, false = not
 *                            sealed, null = run from before this was recorded
 * @param sealedSkillPins     skill content hashes verified at start ({skillId: sha256}), null if none
 * @param chainRunId          chain run that started this run as one of its entries; null outside a
 *                            chain and for runs from before this was recorded
 */
public record WorkflowRunResponse(
        String id,
        String workflowId,
        String workflowName,
        String projectName,
        String projectPath,
        RunStatus status,
        List<StepResultResponse> steps,
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
        Boolean sealed,
        Map<String, String> sealedSkillPins,
        String chainRunId
) {
    /** Compat constructor for call-sites written before runs recorded their chain run. */
    public WorkflowRunResponse(String id, String workflowId, String workflowName, String projectName, String projectPath,
                               RunStatus status, List<StepResultResponse> steps, Instant startedAt, Instant completedAt,
                               Long durationMs, Integer userRating, Map<String, Object> error,
                               Map<String, Object> metadata, Map<String, Object> inputContext,
                               String triggeredByScheduleId, String definitionHash, String definitionVersionId,
                               TriggerSource triggerSource, Boolean sealed, Map<String, String> sealedSkillPins) {
        this(id, workflowId, workflowName, projectName, projectPath, status, steps, startedAt, completedAt, durationMs,
                userRating, error, metadata, inputContext, triggeredByScheduleId, definitionHash, definitionVersionId,
                triggerSource, sealed, sealedSkillPins, null);
    }

    /** Compat constructor for call-sites written before the seal state was recorded on runs. */
    public WorkflowRunResponse(String id, String workflowId, String workflowName, String projectName, String projectPath,
                               RunStatus status, List<StepResultResponse> steps, Instant startedAt, Instant completedAt,
                               Long durationMs, Integer userRating, Map<String, Object> error,
                               Map<String, Object> metadata, Map<String, Object> inputContext,
                               String triggeredByScheduleId, String definitionHash, String definitionVersionId,
                               TriggerSource triggerSource) {
        this(id, workflowId, workflowName, projectName, projectPath, status, steps, startedAt, completedAt, durationMs,
                userRating, error, metadata, inputContext, triggeredByScheduleId, definitionHash, definitionVersionId,
                triggerSource, null, null);
    }

    /** Backwards-compat constructor for call-sites written before run provenance existed. */
    public WorkflowRunResponse(String id, String workflowId, String workflowName, String projectName, String projectPath,
                               RunStatus status, List<StepResultResponse> steps, Instant startedAt, Instant completedAt,
                               Long durationMs, Integer userRating, Map<String, Object> error,
                               Map<String, Object> metadata, Map<String, Object> inputContext,
                               String triggeredByScheduleId) {
        this(id, workflowId, workflowName, projectName, projectPath, status, steps, startedAt, completedAt, durationMs,
                userRating, error, metadata, inputContext, triggeredByScheduleId, null, null, null, null, null);
    }
}
