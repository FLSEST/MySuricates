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
        boolean available,
        List<String> buildFindings,  // Baufehler-Prüfung ab MEDIUM, HIGH zuerst (TODO 326d5987); leer = sauber; deutsch
        List<String> buildFindingsEn // dieselben Zeilen auf Englisch (TODO 81773d7b)
) {
    public DialogWorkflowSuggestion {
        if (buildFindings == null) buildFindings = List.of();
        if (buildFindingsEn == null) buildFindingsEn = List.of();
    }

    /** Nur mit den deutschen Befundzeilen. */
    public DialogWorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                                    String sessionId, String sourceFilePath, String projectPath, double confidence,
                                    String patternType, String notAvailableReason, int toolCallsAnalyzed,
                                    boolean available, List<String> buildFindings) {
        this(suggestedName, suggestedDescription, blocks, sessionId, sourceFilePath, projectPath, confidence,
                patternType, notAvailableReason, toolCallsAnalyzed, available, buildFindings, List.of());
    }

    /** Ohne Befunde der Baufehler-Prüfung. */
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
