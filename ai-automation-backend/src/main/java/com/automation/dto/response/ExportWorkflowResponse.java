package com.automation.dto.response;

import com.automation.entity.BlockDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record ExportWorkflowResponse(
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String version,
        Map<String, Object> defaultContext,
        Map<String, Object> metadata,
        Instant exportedAt
) {}
