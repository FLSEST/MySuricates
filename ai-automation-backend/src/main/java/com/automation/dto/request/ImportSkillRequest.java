package com.automation.dto.request;

import com.automation.entity.SkillCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record ImportSkillRequest(
        @NotBlank String name,
        String description,
        String icon,
        @NotNull SkillCategory category,
        String aiContext,
        Map<String, Object> configSchema,
        String version,
        List<ImportCapabilityRequest> capabilities
) {
    public record ImportCapabilityRequest(
            @NotBlank String name,
            String description,
            String handlerClass,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean async,
            boolean writes
    ) {}
}
