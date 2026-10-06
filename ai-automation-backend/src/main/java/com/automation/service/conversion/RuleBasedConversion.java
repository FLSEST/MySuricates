package com.automation.service.conversion;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rule-based conversion without AI: every successful action of an agentic run becomes a
 * simple block. Fallback of {@link WorkflowConversionService#suggestWorkflow} when the AI suggestion
 * fails; extracted from the service so that it stays under the size limit (TODO 935af6d3).
 */
final class RuleBasedConversion {

    private RuleBasedConversion() {
    }

    @SuppressWarnings("unchecked")
    static WorkflowSuggestion suggest(List<StepResult> agenticSteps) {
        List<BlockDefinition> blocks = new ArrayList<>();
        String goal = "";
        int totalActionsAnalyzed = 0;

        for (StepResult step : agenticSteps) {
            Map<String, Object> meta = step.getMetadata();
            goal = (String) meta.getOrDefault("goal", "");

            Object iterationsObj = meta.get("iterations");
            if (!(iterationsObj instanceof List<?> iterList)) continue;

            for (Object iterObj : iterList) {
                if (!(iterObj instanceof Map<?, ?> iterMap)) continue;
                Map<String, Object> iter = (Map<String, Object>) iterMap;

                String action = (String) iter.get("action");
                if (action == null || action.isEmpty()) continue;

                Integer exitCode = iter.get("exitCode") instanceof Number n ? n.intValue() : null;
                if (exitCode != null && exitCode != 0) continue;

                // Each successful action becomes a simple block
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("command", action);
                config.put("on_failure", "stop");

                BlockDefinition block = BlockDefinition.builder()
                        .type("simple")
                        .config(config)
                        .build();
                blocks.add(block);
                totalActionsAnalyzed++;
            }
        }

        if (blocks.isEmpty()) {
            return WorkflowSuggestion.notAvailable();
        }

        String name = "Auto: " + truncate(goal, 60);
        String description = "Automatically generated from successful agentic run. " +
                "Original goal: " + goal;

        return new WorkflowSuggestion(name, description, blocks, goal, totalActionsAnalyzed, true);
    }

    static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
