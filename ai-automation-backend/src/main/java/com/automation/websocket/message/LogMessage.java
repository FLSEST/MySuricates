package com.automation.websocket.message;

import com.automation.entity.StepStatus;

public record LogMessage(
        String runId,
        int stepIndex,
        StepStatus status,
        String input,
        String output,
        long durationMs
) {}
