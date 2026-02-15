package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;

public record CreateWorkflowRequest(
        @NotBlank String projectId,
        @NotBlank String name,
        String description,
        String icon,
        @NotNull List<BlockDefinition> blocks,
        List<String> activatedSkillIds
) {}
