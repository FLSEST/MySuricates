package com.automation.dto.request;

import com.automation.entity.ActivityLog.LogLevel;

import java.util.Map;

public record CreateActivityLogRequest(
        LogLevel level,
        String message,
        String source,
        String referenceId,
        String workflowId,
        Map<String, Object> metadata
) {}
