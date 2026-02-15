package com.automation.websocket.message;

import com.automation.entity.RunStatus;

public record StatusUpdate(
        String runId,
        RunStatus status,
        String message
) {}
