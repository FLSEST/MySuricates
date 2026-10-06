package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Result of the AI analysis of a Claude Code dialog session.
 * Similar concept to WorkflowSuggestion, but with dialog-specific fields.
 */
public record DialogWorkflowSuggestion(
        String suggestedName,
        String suggestedDescription,
        List<BlockDefinition> blocks,
        String sessionId,
        String sourceFilePath,
        String projectPath,
        double confidence,       // 0.0–1.0 (0.5 for rule-based, the AI supplies a real value)
        String patternType,      // "scripting" | "file_editing" | "research" | "mixed"
        String notAvailableReason,
        int toolCallsAnalyzed,
        boolean available,
        List<String> buildFindings,  // build-error check from MEDIUM, HIGH first (TODO 326d5987); empty = clean; German
        List<String> buildFindingsEn // same lines in English (TODO 81773d7b)
) {
    public DialogWorkflowSuggestion {
        if (buildFindings == null) buildFindings = List.of();
        if (buildFindingsEn == null) buildFindingsEn = List.of();
    }

    /** Only with the German finding lines. */
    public DialogWorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                                    String sessionId, String sourceFilePath, String projectPath, double confidence,
                                    String patternType, String notAvailableReason, int toolCallsAnalyzed,
                                    boolean available, List<String> buildFindings) {
        this(suggestedName, suggestedDescription, blocks, sessionId, sourceFilePath, projectPath, confidence,
                patternType, notAvailableReason, toolCallsAnalyzed, available, buildFindings, List.of());
    }

    /** Without findings of the build-error check. */
    public DialogWorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                                    String sessionId, String sourceFilePath, String projectPath, double confidence,
                                    String patternType, String notAvailableReason, int toolCallsAnalyzed,
                                    boolean available) {
        this(suggestedName, suggestedDescription, blocks, sessionId, sourceFilePath, projectPath, confidence,
                patternType, notAvailableReason, toolCallsAnalyzed, available, List.of(), List.of());
    }

    public static DialogWorkflowSuggestion notAvailable(String reason) {
        return new DialogWorkflowSuggestion(
                null, null, List.of(),
                null, null, null,
                0.0, null, reason, 0, false
        );
    }
}
