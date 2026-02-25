package com.automation.dto.response;

import com.automation.entity.BlockDefinition;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowResponse(
        String id,
        String projectId,
        String projectName,
        String projectSubtitle,
        String projectDescription,
        String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String version,
        Map<String, Object> metadata,
        List<SkillResponse> activatedSkills,
        long runCount,
        String instructions,
        String model,
        String templateId,
        Instant createdAt,
        Instant updatedAt
) {}
