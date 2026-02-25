package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record CreateWorkflowTemplateRequest(
        @NotNull Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        @NotNull List<BlockDefinition> blocks,
        String instructions,
        String model
) {}
