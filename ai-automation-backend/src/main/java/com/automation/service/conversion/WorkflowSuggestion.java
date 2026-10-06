package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;

import java.util.List;

/**
 * Suggestion to capture a successful agentic run as a workflow. {@code buildFindings} are the
 * findings of the build-error check from MEDIUM, HIGH first (TODO 326d5987) — empty if the suggestion is clean;
 * German. {@code buildFindingsEn} the same lines in English (TODO 81773d7b).
 */
public record WorkflowSuggestion(
        String suggestedName,
        String suggestedDescription,
        List<BlockDefinition> blocks,
        String originalGoal,
        int agenticStepsAnalyzed,
        boolean available,
        List<String> buildFindings,
        List<String> buildFindingsEn
) {
    public WorkflowSuggestion {
        if (buildFindings == null) buildFindings = List.of();
        if (buildFindingsEn == null) buildFindingsEn = List.of();
    }

    /** Only with the German finding lines. */
    public WorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                              String originalGoal, int agenticStepsAnalyzed, boolean available,
                              List<String> buildFindings) {
        this(suggestedName, suggestedDescription, blocks, originalGoal, agenticStepsAnalyzed, available,
                buildFindings, List.of());
    }

    /** Without findings of the build-error check. */
    public WorkflowSuggestion(String suggestedName, String suggestedDescription, List<BlockDefinition> blocks,
                              String originalGoal, int agenticStepsAnalyzed, boolean available) {
        this(suggestedName, suggestedDescription, blocks, originalGoal, agenticStepsAnalyzed, available,
                List.of(), List.of());
    }

    public static WorkflowSuggestion notAvailable() {
        return new WorkflowSuggestion(null, null, List.of(), null, 0, false);
    }

    /** The same suggestion with the (German) findings of the build-error check. */
    public WorkflowSuggestion withBuildFindings(List<String> findings) {
        return withBuildFindings(findings, List.of());
    }

    /** The same suggestion with the findings of the build-error check, German and English. */
    public WorkflowSuggestion withBuildFindings(List<String> findings, List<String> findingsEn) {
        return new WorkflowSuggestion(suggestedName, suggestedDescription, blocks, originalGoal,
                agenticStepsAnalyzed, available, findings, findingsEn);
    }
}
