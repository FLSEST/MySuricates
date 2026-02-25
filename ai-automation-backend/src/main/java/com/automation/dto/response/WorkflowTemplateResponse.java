package com.automation.dto.response;

import com.automation.entity.BlockDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowTemplateResponse(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        boolean builtIn,
        int sortOrder,
        String version,
        Instant createdAt,
        Instant updatedAt
) {}
