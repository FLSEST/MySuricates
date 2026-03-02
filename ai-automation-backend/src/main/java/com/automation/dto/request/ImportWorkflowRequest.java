package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ImportWorkflowRequest(
        String projectId,
        @NotBlank String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        Boolean updateIfExists
) {
    public ImportWorkflowRequest {
        if (blocks == null) blocks = List.of();
        if (updateIfExists == null) updateIfExists = false;
    }
}
