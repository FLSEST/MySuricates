package com.automation.service.ai;

import java.io.IOException;
import java.util.List;

/**
 * Abstraction over AI CLI backends (Claude, Codex, future providers).
 * Each provider wraps a host CLI service and normalizes its interface.
 */
public interface AiProvider {

    /**
     * Sentinel for {@code allowedTools}: the call gets no built-in tools at all (Claude: {@code --tools ""}).
     * Two callers use it, both only with the Claude provider: the {@code compress-ai} block and the
     * {@code dashboard-report} editor assist. An empty list would mean "no restriction", so this must
     * stay a list holding one empty string — see {@code ClaudeToolFlags}.
     */
    List<String> NO_TOOLS = List.of("");

    /** Unique provider identifier, e.g. "CLAUDE", "CODEX". */
    String getId();

    /** Declares what features this provider supports. */
    AiProviderCapabilities getCapabilities();

    /**
     * How a person sets this provider up on their machine — program, install, login. Pflicht fuer
     * jeden Anbieter: daraus erzeugt ProviderCatalogTest den Katalog des Installers (2255f2ee).
     */
    ProviderSetup setup();

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
            String model,
            String workingDirectory,
            Integer maxTurns,
            Integer timeoutSeconds,
            String effort
    ) {
        public ReasoningRequest(String prompt) {
            this(prompt, null, null, null, null, null, null);
        }

        public ReasoningRequest(String prompt, String systemPrompt) {
            this(prompt, systemPrompt, null, null, null, null, null);
        }

        /** Backwards-compat constructor for call-sites written before effort support existed. */
        public ReasoningRequest(String prompt, String systemPrompt, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds) {
            this(prompt, systemPrompt, null, workingDirectory, maxTurns, timeoutSeconds, null);
        }

        /** Backwards-compat constructor for call-sites written before model support existed. */
        public ReasoningRequest(String prompt, String systemPrompt, String workingDirectory,
                                Integer maxTurns, Integer timeoutSeconds, String effort) {
            this(prompt, systemPrompt, null, workingDirectory, maxTurns, timeoutSeconds, effort);
        }
    }

    record ActionRequest(
            String prompt,
            String model,
            String systemPrompt,
            String workingDirectory,
            Integer timeoutSeconds,
            boolean priority,
            boolean readOnly,
            List<String> allowedTools,
            List<String> disallowedTools,
            String effort
    ) {
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory) {
            this(prompt, model, systemPrompt, workingDirectory, null, false, true, null, null, null);
        }

        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory, Integer timeoutSeconds) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, false, true, null, null, null);
        }

        /** Backward-compatible: 6-arg calls default to readOnly=true (analysis mode). */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory, Integer timeoutSeconds, boolean priority) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, true, null, null, null);
        }

        /** Backward-compatible 7-arg constructor for callers that previously set readOnly explicitly. */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory,
                             Integer timeoutSeconds, boolean priority, boolean readOnly) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, readOnly, null, null, null);
        }

        /** Chat-scoped constructor with explicit tool whitelist. */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory,
                             Integer timeoutSeconds, boolean priority,
                             List<String> allowedTools, List<String> disallowedTools) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, true,
                    allowedTools, disallowedTools, null);
        }

        /** Backwards-compat constructor for call-sites written before effort support existed. */
        public ActionRequest(String prompt, String model, String systemPrompt, String workingDirectory,
                             Integer timeoutSeconds, boolean priority, boolean readOnly,
                             List<String> allowedTools, List<String> disallowedTools) {
            this(prompt, model, systemPrompt, workingDirectory, timeoutSeconds, priority, readOnly,
                    allowedTools, disallowedTools, null);
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
