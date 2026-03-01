package com.automation.dto.response;

import com.automation.entity.WishStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
        Map<String, Object> metadata,
        Instant createdAt,
        Instant updatedAt
) {}
