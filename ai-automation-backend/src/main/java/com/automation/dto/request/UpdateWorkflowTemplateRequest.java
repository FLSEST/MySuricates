package com.automation.dto.request;

import com.automation.entity.BlockDefinition;

import java.util.List;
import java.util.Map;

public record UpdateWorkflowTemplateRequest(
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        Integer sortOrder
) {}
