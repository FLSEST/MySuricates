package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Vorschlag, einen erfolgreichen agentic-Lauf als Workflow festzuhalten. {@code buildFindings} sind die
 * Befunde der Baufehler-Prüfung ab MEDIUM, HIGH zuerst (TODO 326d5987) — leer, wenn der Vorschlag sauber ist.
 */
public record WorkflowSuggestion(
        String suggestedName,
        String suggestedDescription,
        List<BlockDefinition> blocks,
        String originalGoal,
        int agenticStepsAnalyzed,
        boolean available,
        List<String> buildFindings
) {
    public WorkflowSuggestion {
        if (buildFindings == null) buildFindings = List.of();
    }

    /** Ohne Befunde der Baufehler-Prüfung. */
    public WorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                              String originalGoal, int agenticStepsAnalyzed, boolean available) {
        this(suggestedName, suggestedDescription, blocks, originalGoal, agenticStepsAnalyzed, available, List.of());
    }

    public static WorkflowSuggestion notAvailable() {
        return new WorkflowSuggestion(null, null, List.of(), null, 0, false);
    }

    /** Derselbe Vorschlag mit den Befunden der Baufehler-Prüfung. */
    public WorkflowSuggestion withBuildFindings(List<String> findings) {
        return new WorkflowSuggestion(suggestedName, suggestedDescription, blocks, originalGoal,
                agenticStepsAnalyzed, available, findings);
    }
}
