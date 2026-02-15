package com.automation.dto.response;

import com.automation.entity.SkillCategory;

import java.util.List;
import java.util.Map;

public record ExportSkillResponse(
        String name,
        String description,
        String icon,
        SkillCategory category,
        String aiContext,
        Map<String, Object> configSchema,
        String version,
        List<ExportCapabilityResponse> capabilities
) {
    public record ExportCapabilityResponse(
            String name,
            String description,
            String handlerClass,
            Map<String, Object> inputSchema,
            Map<String, Object> outputSchema,
            boolean async
    ) {}
}
