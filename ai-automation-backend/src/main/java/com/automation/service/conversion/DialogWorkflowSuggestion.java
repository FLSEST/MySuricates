package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Ergebnis der KI-Analyse einer Claude-Code-Dialog-Session.
 * Analoges Konzept zu WorkflowSuggestion, aber mit Dialog-spezifischen Feldern.
 */
public record DialogWorkflowSuggestion(
        String suggestedName,
        String suggestedDescription,
        List<BlockDefinition> blocks,
        String sessionId,
        String sourceFilePath,
        String projectPath,
        double confidence,       // 0.0–1.0 (0.5 bei rule-based, KI liefert echten Wert)
        String patternType,      // "scripting" | "file_editing" | "research" | "mixed"
        String notAvailableReason,
        int toolCallsAnalyzed,
        boolean available
) {
    public static DialogWorkflowSuggestion notAvailable(String reason) {
        return new DialogWorkflowSuggestion(
                null, null, List.of(),
                null, null, null,
                0.0, null, reason, 0, false
        );
    }
}
