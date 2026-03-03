package com.automation.service.ai;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over AI CLI backends (Claude, Codex, future providers).
 * Each provider wraps a host CLI service and normalizes its interface.
 */
public interface AiProvider {

    /** Unique provider identifier, e.g. "CLAUDE", "CODEX". */
    String getId();

    /** Declares what features this provider supports. */
    AiProviderCapabilities getCapabilities();

    /**
     * Reasoning phase: "think about this prompt and respond".
     * Used by agentic REASON, optimization, conversion, wish analysis, etc.
     */
    ReasoningResult reason(ReasoningRequest request) throws IOException;

    /**
     * Action phase: "execute this task in the project directory".
     * Used by agentic ACT, simple/shell blocks, skill execution.
     */
    ActionResult executeAction(ActionRequest request) throws IOException;

    /**
     * Builds a flat conversation prompt from system prompt + turn history.
     * Providers that support native multi-turn can override this.
     */
    String buildConversationPrompt(String systemPrompt, List<HostClaudeService.Turn> history);

    // --- Request records ---

    record ReasoningRequest(
            String prompt,
            String systemPrompt,
            String workingDirectory,
            Integer maxTurns,
            Integer timeoutSeconds
    ) {
        public ReasoningRequest(String prompt) {
            this(prompt, null, null, null, null);
        }

        public ReasoningRequest(String prompt, String systemPrompt) {
            this(prompt, systemPrompt, null, null, null);
        }
    }

    record ActionRequest(
            String prompt,
            String model,
            String systemPrompt,
            String workingDirectory,
            Integer timeoutSeconds
    ) {
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory) {
            this(prompt, model, systemPrompt, workingDirectory, null);
        }
    }

    // --- Response records ---

    record ReasoningResult(
            String text,
            AiResponseMetadata metadata
    ) {}

    record ActionResult(
            String text,
            int exitCode,
            AiResponseMetadata metadata
    ) {}
}
