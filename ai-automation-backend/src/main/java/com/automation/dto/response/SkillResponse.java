package com.automation.dto.response;

import com.automation.entity.SkillCategory;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record SkillResponse(
        String id,
        String name,
        String description,
        String icon,
        SkillCategory category,
        String aiContext,
        Map<String, Object> configSchema,
        boolean builtin,
        String version,
        List<CapabilityResponse> capabilities,
        Instant createdAt
) {}
