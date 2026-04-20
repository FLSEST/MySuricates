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
        List<String> activatedSkillIds,
        List<String> activatedSkillNames,
        long runCount,
        String instructions,
        String model,
        String provider,
        String templateId,
        Map<String, Object> defaultContext,
        String testStatus,
        Instant lastTestedAt,
        Instant createdAt,
        Instant updatedAt,
        Instant stableSyncedAt,
        List<String> tags,
        String category,
        Instant tagsGeneratedAt,
        boolean tagsUserEdited
) {
    /** Backwards-compat constructor for call-sites written before tag support existed. */
    public WorkflowResponse(
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
            List<String> activatedSkillIds,
            List<String> activatedSkillNames,
            long runCount,
            String instructions,
            String model,
            String provider,
            String templateId,
            Map<String, Object> defaultContext,
            String testStatus,
            Instant lastTestedAt,
            Instant createdAt,
            Instant updatedAt,
            Instant stableSyncedAt
    ) {
        this(id, projectId, projectName, projectSubtitle, projectDescription,
                name, description, icon, blocks, version, metadata,
                activatedSkillIds, activatedSkillNames, runCount,
                instructions, model, provider, templateId, defaultContext,
                testStatus, lastTestedAt, createdAt, updatedAt, stableSyncedAt,
                List.of(), null, null, false);
    }
}
