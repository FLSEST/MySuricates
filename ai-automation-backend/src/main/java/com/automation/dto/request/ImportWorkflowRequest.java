package com.automation.dto.request;

import com.automation.entity.BlockDefinition;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

/**
 * A workflow export file plus the target project.
 *
 * @param version the version label of the export ({@code 4.4.0}), taken over on create and — with
 *                {@code updateIfExists} — on update; {@code null} or blank keeps the default resp. the
 *                current label (TODO 5e2e73a5)
 */
public record ImportWorkflowRequest(
        String projectId,
        @NotBlank String name,
        String description,
        String icon,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        Boolean updateIfExists,
        Map<String, Object> defaultContext,
        Map<String, Object> metadata,
        String version
) {
    public ImportWorkflowRequest {
        if (blocks == null) blocks = List.of();
        if (updateIfExists == null) updateIfExists = false;
    }

    /** Backwards-compat constructor for call-sites written before the version label was imported. */
    public ImportWorkflowRequest(String projectId, String name, String description, String icon,
                                 List<BlockDefinition> blocks, String instructions, String model,
                                 Boolean updateIfExists, Map<String, Object> defaultContext,
                                 Map<String, Object> metadata) {
        this(projectId, name, description, icon, blocks, instructions, model, updateIfExists,
                defaultContext, metadata, null);
    }
}
