package com.automation.executor.blocks;

import lombok.Builder;
import lombok.Getter;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Getter
@Builder
public class AgenticPlan {

    private final String goalStatus;   // IN_PROGRESS, ACHIEVED, IMPOSSIBLE
    private final String rationale;
    private final String action;
    private final String summary;

    public boolean isGoalComplete() {
        return "ACHIEVED".equalsIgnoreCase(goalStatus)
                || "IMPOSSIBLE".equalsIgnoreCase(goalStatus);
    }

    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "GOAL_STATUS:\\s*(.+?)\\s*$", Pattern.MULTILINE);
    private static final Pattern RATIONALE_PATTERN = Pattern.compile(
            "RATIONALE:\\s*(.+?)(?=\\nACTION:|\\nGOAL_STATUS:|\\nSUMMARY:|$)", Pattern.DOTALL);
    private static final Pattern ACTION_PATTERN = Pattern.compile(
            "ACTION:[ \\t]*(.*?)(?=\\nRATIONALE:|\\nGOAL_STATUS:|\\nSUMMARY:|$)", Pattern.DOTALL);
    private static final Pattern SUMMARY_PATTERN = Pattern.compile(
            "SUMMARY:\\s*(.+?)(?=\\nRATIONALE:|\\nGOAL_STATUS:|\\nACTION:|$)", Pattern.DOTALL);

    /**
     * Parses a structured reasoning response from Claude.
     * Expected format:
     *   GOAL_STATUS: IN_PROGRESS|ACHIEVED|IMPOSSIBLE
     *   RATIONALE: ...
     *   ACTION: ...
     *   SUMMARY: ...
     *
     * Falls back to treating the entire response as a single action if parsing fails.
     */
    public static AgenticPlan parse(String response) {
        if (response == null || response.isBlank()) {
            return AgenticPlan.builder()
                    .goalStatus("IMPOSSIBLE")
                    .rationale("Empty response from reasoning")
                    .action("")
                    .summary("No response")
                    .build();
        }

        String status = extractField(STATUS_PATTERN, response, "IN_PROGRESS");
        String rationale = extractField(RATIONALE_PATTERN, response, "");
        String action = extractField(ACTION_PATTERN, response, "");
        String summary = extractField(SUMMARY_PATTERN, response, "");

        // If we couldn't parse the structured format, treat the whole response as an action
        // Only apply fallback for truly unstructured responses (no ACTION: field present at all)
        boolean hasActionField = response.contains("ACTION:");
        if (action.isEmpty() && !hasActionField && !status.equalsIgnoreCase("ACHIEVED") && !status.equalsIgnoreCase("IMPOSSIBLE")) {
            action = response.trim();
            rationale = "Unstructured response, using as direct action";
            summary = "Direct execution";
        }

        return AgenticPlan.builder()
                .goalStatus(status.trim())
                .rationale(rationale.trim())
                .action(action.trim())
                .summary(summary.trim())
                .build();
    }

    private static String extractField(Pattern pattern, String text, String defaultValue) {
        Matcher matcher = pattern.matcher(text);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return defaultValue;
    }
}
