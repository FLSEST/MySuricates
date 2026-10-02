package com.automation.service.ai;

/**
 * pi's {@code --thinking} level for a reasoning call: an explicit effort wins, otherwise the {@code maxTurns}
 * hint maps onto it (1 → low, ≤3 → medium, more → high) and without a hint the configured default applies.
 * Kept apart from {@link HostPiService} so the agentic step can name the level it reasoned with (TODO 20861200).
 */
final class PiThinkingLevel {

    private PiThinkingLevel() {
    }

    static String forReasoning(Integer maxTurns, String effort, String configuredDefault) {
        return ReasoningEffort.parse(effort)
                .map(ReasoningEffort::cliValue)
                .orElseGet(() -> fromMaxTurns(maxTurns, configuredDefault));
    }

    private static String fromMaxTurns(Integer maxTurns, String configuredDefault) {
        if (maxTurns == null) {
            return configuredDefault;
        }
        if (maxTurns <= 1) return "low";
        if (maxTurns <= 3) return "medium";
        return "high";
    }
}
