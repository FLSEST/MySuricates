package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record CreateWorkflowRequest(
        @NotBlank String projectId,
        @NotBlank String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        List<String> activatedSkillIds
) {
    public CreateWorkflowRequest {
        if (blocks == null) blocks = List.of();
        if (activatedSkillIds == null) activatedSkillIds = List.of();
    }
}
