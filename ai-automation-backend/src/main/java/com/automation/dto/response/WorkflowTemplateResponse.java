package com.automation.dto.response;

import com.automation.entity.BlockDefinition;
import com.automation.entity.TemplateKind;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record WorkflowTemplateResponse(
        String id,
        Map<String, String> name,
        Map<String, String> description,
        String icon,
        String category,
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        boolean builtIn,
        int sortOrder,
        String qualityStatus,
        List<TemplateVerificationInfo> verifications,
        String version,
        Instant createdAt,
        Instant updatedAt,
        /** {@code TEMPLATE} or {@code BLUEPRINT} (TODO 268ae0db). */
        TemplateKind kind
) {
    /** The same template with its blocks and instructions in another language (bilingual templates). */
    public WorkflowTemplateResponse withBlocksAndInstructions(List<BlockDefinition> localizedBlocks,
                                                              String localizedInstructions) {
        return new WorkflowTemplateResponse(id, name, description, icon, category, localizedBlocks,
                localizedInstructions, model, builtIn, sortOrder, qualityStatus, verifications, version,
                createdAt, updatedAt, kind);
    }

    public record TemplateVerificationInfo(
            String projectId,
            String projectName,
            String lastRunId,
            Instant verifiedAt
    ) {}
}
