package com.automation.service.ai;

/**
 * Declares what features an AI provider supports.
 * Used by callers to adapt their behavior (e.g. skip system prompt for Codex).
 */
public record AiProviderCapabilities(
        boolean supportsSystemPrompt,
        boolean supportsModelSelection,
        boolean supportsMaxTurns,
        boolean supportsStreamJson,
        boolean supportsAllowedTools,
        boolean tokenTracking,
        boolean supportsMultiTurnReasoning,
        boolean supportsEffort
) {
    /** All features supported (Claude). */
    public static AiProviderCapabilities full() {
        return new AiProviderCapabilities(true, true, true, true, true, true, true, true);
    }

    /** Minimal capabilities (Codex). */
    public static AiProviderCapabilities minimal() {
        return new AiProviderCapabilities(false, false, false, false, false, false, false, false);
    }
}
