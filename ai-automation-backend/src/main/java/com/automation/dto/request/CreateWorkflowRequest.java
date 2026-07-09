package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record CreateWorkflowRequest(
        @NotBlank String projectId,
        @NotBlank String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        List<String> activatedSkillIds,
        String instructions,
        String model,
        String provider,
        String templateId,
        Map<String, Object> metadata,
        Map<String, Object> defaultContext,
        String version,
        String effort
) {
    public CreateWorkflowRequest {
        if (blocks == null) blocks = List.of();
        if (activatedSkillIds == null) activatedSkillIds = List.of();
    }

    /** Backwards-compat constructor for call-sites written before effort support existed. */
    public CreateWorkflowRequest(String projectId, String name, String description, String icon,
                                 List<BlockDefinition> blocks, List<String> activatedSkillIds,
                                 String instructions, String model, String provider,
                                 String templateId, Map<String, Object> metadata,
                                 Map<String, Object> defaultContext, String version) {
        this(projectId, name, description, icon, blocks, activatedSkillIds, instructions,
                model, provider, templateId, metadata, defaultContext, version, null);
    }
}
