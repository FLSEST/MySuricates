package com.automation.dto.request;

import com.automation.entity.SkillCategory;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Editable fields of a custom skill. Capabilities, config schema and the version label are left
 * as they are — capabilities come in through import, the change history is {@code skill_versions}.
 */
public record UpdateSkillRequest(
        @NotBlank String name,
        String description,
        String icon,
        @NotNull SkillCategory category,
        String aiContext
) {}
