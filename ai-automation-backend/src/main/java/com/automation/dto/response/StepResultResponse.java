package com.automation.dto.response;

import com.automation.entity.StepStatus;

import java.time.Instant;
import java.util.Map;

public record StepResultResponse(
        String id,
        int stepIndex,
        String input,
        String output,
        StepStatus status,
        Instant startedAt,
        Instant completedAt,
        Long durationMs,
        Map<String, Object> metadata
) {}
