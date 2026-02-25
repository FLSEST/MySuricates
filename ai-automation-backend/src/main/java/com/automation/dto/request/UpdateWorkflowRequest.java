package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

public record UpdateWorkflowRequest(
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        Map<String, Object> metadata
) {}
