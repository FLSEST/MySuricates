package com.automation.dto.response;

import java.time.Instant;

public record WorkflowVersionResponse(
        String id,
        String workflowId,
        int versionNumber,
        String snapshot,
        String changeSource,
        String changeMessage,
        Instant createdAt
) {}
