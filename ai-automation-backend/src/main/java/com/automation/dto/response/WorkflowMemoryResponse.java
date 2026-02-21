package com.automation.dto.response;

import java.time.Instant;
import java.util.List;

public record WorkflowMemoryResponse(
        String id,
        String workflowId,
        String title,
        String content,
        List<String> tags,
        List<String> sourceRunIds,
        String provider,
        boolean active,
        Instant createdAt
) {}
