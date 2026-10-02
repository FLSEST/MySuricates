package com.automation.dto.response;

import com.automation.entity.WishStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * @param stoppedPhase the phase a {@code FAILED}/{@code CANCELLED} wish was in when it stopped (V97); null otherwise
 *                     and for wishes that stopped before V97
 */
public record WishResponse(
        String id,
        String originalText,
        WishStatus status,
        String projectId,
        String projectName,
        Map<String, Object> decomposition,
        List<String> generatedWorkflowIds,
        List<String> generatedWorkflowNames,
        List<String> referenceWorkflowIds,
        List<String> referenceWorkflowNames,
        String generatedChainId,
        int iterationCount,
        int maxIterations,
        String currentRunId,
        String resultSummary,
        String error,
        List<Map<String, Object>> iterationHistory,
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt,
        boolean autoRefine,
        WishStatus stoppedPhase
) {
    /** Kompatibilitäts-Konstruktor für Aufrufer von vor der gespeicherten Endphase (V97). */
    public WishResponse(String id, String originalText, WishStatus status, String projectId, String projectName,
                        Map<String, Object> decomposition, List<String> generatedWorkflowIds,
                        List<String> generatedWorkflowNames, List<String> referenceWorkflowIds,
                        List<String> referenceWorkflowNames, String generatedChainId, int iterationCount,
                        int maxIterations, String currentRunId, String resultSummary, String error,
                        List<Map<String, Object>> iterationHistory, Map<String, Object> metadata,
                        Instant createdAt, Instant updatedAt, boolean autoRefine) {
        this(id, originalText, status, projectId, projectName, decomposition, generatedWorkflowIds,
                generatedWorkflowNames, referenceWorkflowIds, referenceWorkflowNames, generatedChainId, iterationCount,
                maxIterations, currentRunId, resultSummary, error, iterationHistory, metadata, createdAt, updatedAt,
                autoRefine, null);
    }
}
