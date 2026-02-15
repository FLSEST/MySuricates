package com.automation.dto.response;

import com.automation.entity.RunStatus;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowRunResponse(
        String id,
        String workflowId,
        RunStatus status,
        List<StepResultResponse> steps,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Integer userRating,
        Map<String, Object> error,
        Map<String, Object> metadata
) {}
