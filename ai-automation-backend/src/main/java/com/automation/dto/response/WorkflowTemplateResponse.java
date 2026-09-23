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
        TemplateKind kind,
        /**
         * Keys of the optional modules its blocks need ({@code BlockTypeModules}); the picker hides
         * the template while one of them is off (TODO 560d18dd).
         */
        List<String> requiredModules,
        /** Whether running it can call an AI model ({@code ModelUse}). */
        boolean needsAi
) {
    /** The same template with its blocks and instructions in another language (bilingual templates). */
    public WorkflowTemplateResponse withBlocksAndInstructions(List<BlockDefinition> localizedBlocks,
                                                              String localizedInstructions) {
        return new WorkflowTemplateResponse(id, name, description, icon, category, localizedBlocks,
                localizedInstructions, model, builtIn, sortOrder, qualityStatus, verifications, version,
                createdAt, updatedAt, kind, requiredModules, needsAi);
    }

    /** The same template with the projects it was verified in. */
    public WorkflowTemplateResponse withVerifications(List<TemplateVerificationInfo> verificationInfos) {
        return new WorkflowTemplateResponse(id, name, description, icon, category, blocks, instructions, model,
                builtIn, sortOrder, qualityStatus, verificationInfos, version, createdAt, updatedAt, kind,
                requiredModules, needsAi);
    }

    public record TemplateVerificationInfo(
            String projectId,
            String projectName,
            String lastRunId,
            Instant verifiedAt
    ) {}
}
