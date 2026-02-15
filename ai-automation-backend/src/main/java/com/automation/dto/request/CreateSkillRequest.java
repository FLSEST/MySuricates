package com.automation.dto.request;

import com.automation.entity.SkillCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.Map;

public record CreateSkillRequest(
        @NotBlank String name,
        String description,
        String icon,
        @NotNull SkillCategory category,
        String aiContext,
        Map<String, Object> configSchema
) {}
