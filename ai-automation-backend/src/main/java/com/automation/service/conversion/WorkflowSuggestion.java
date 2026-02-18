package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;

import java.util.List;

public record WorkflowSuggestion(
        String suggestedName,
        String suggestedDescription,
        List<BlockDefinition> blocks,
        String originalGoal,
        int agenticStepsAnalyzed,
        boolean available
) {
    public static WorkflowSuggestion notAvailable() {
        return new WorkflowSuggestion(null, null, List.of(), null, 0, false);
    }
}
