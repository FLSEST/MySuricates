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

    /** Nothing supported — baseline for providers that only take a prompt. */
    public static AiProviderCapabilities minimal() {
        return new AiProviderCapabilities(false, false, false, false, false, false, false, false);
    }

    /** Codex CLI: prompt-only, but {@code -m <model>} is honoured. */
    public static AiProviderCapabilities codex() {
        return new AiProviderCapabilities(false, true, false, false, false, false, false, false);
    }
}
