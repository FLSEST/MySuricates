package com.automation.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * AI provider backed by Mario Zechner's `pi` coding agent on the host.
 * Wraps {@link HostPiService}. Supports system prompt + model selection (with backend routing
 * via the {@code "<backend>/<model-id>"} model-string convention). Token tracking is currently
 * estimated — pi's {@code --mode json} stream could provide real counts in a follow-up.
 * pi runs with {@code --no-context-files}, so skills, workflow memory and project context reach it
 * through the system prompt ({@link RunContextFiles}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PiAiProvider implements AiProvider {

    public static final String ID = "PI";

    /**
     * Die pi-Version, die der Installer ({@code --with-pi}) und das Tryout-Image installieren (c3a4cbf6).
     * Gepinnt, weil eine neue pi-Version die Aufrufe dieser Klasse ({@code --thinking},
     * {@code <backend>/<model>}) brechen kann, ohne dass es auffaellt. Update-Weg: docs/entwicklung.md →
     * „pi-Version“; {@code ProviderCatalogTest} haelt {@code ARG PI_VERSION} in deploy/Dockerfile gleich.
     */
    public static final String PINNED_VERSION = "0.74.2";

    private final HostPiService hostPiService;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public ProviderSetup setup() {
        // pi ist selbst ein Hub: angemeldet ist, wer in auth.json mindestens ein Backend hat — ein
        // Anfuehrungszeichen heisst "mindestens ein Schluessel", ein leeres {} zaehlt nicht
        return new ProviderSetup("pi", "npm install -g @earendil-works/pi-coding-agent@" + PINNED_VERSION,
                "pi, then /login   (or an API key as environment variable, see pi --help)",
                java.util.List.of(".pi/agent/auth.json:\""),
                // die gaengigen Backends aus "pi --help" (Environment Variables); jeder Schluessel
                // reicht, weil pi das Backend je Modell waehlt (<backend>/<model>)
                java.util.List.of("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN", "OPENAI_API_KEY",
                        "GEMINI_API_KEY", "OPENROUTER_API_KEY"),
                ".pi/agent", "PI_CODING_AGENT_DIR");
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        // System prompt + model selection: yes (pi supports both).
        // Multi-turn reasoning: yes (--thinking levels).
        // Allowed tools: yes (--tools <list>).
        // Token tracking: estimated for now (no JSONL parser yet) — flagged via metadata.
        // Stream JSON: pi has --mode json but we don't consume it yet.
        return new AiProviderCapabilities(
                true,   // supportsSystemPrompt
                true,   // supportsModelSelection
                true,   // supportsMaxTurns (mapped onto --thinking)
                false,  // supportsStreamJson (not consumed yet)
                true,   // supportsAllowedTools (--tools)
                false,  // tokenTracking (estimated)
                true,   // supportsMultiTurnReasoning
                true    // supportsEffort (--thinking)
        );
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        HostPiService.ReasoningResult result = hostPiService.reason(
                request.prompt(),
                RunContextFiles.withRunContext(request.systemPrompt(), request.workingDirectory()),
                request.model(),
                request.workingDirectory(),
                request.maxTurns(),
                request.timeoutSeconds(),
                request.effort()
        );
        return new ReasoningResult(
                result.text(),
                AiResponseMetadata.estimated(request.prompt(), result.text())
        );
    }

    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        HostPiService.ActionResult result = hostPiService.executeAction(
                request.prompt(),
                request.model(),
                RunContextFiles.withRunContext(request.systemPrompt(), request.workingDirectory()),
                request.workingDirectory(),
                request.timeoutSeconds(),
                request.priority(),
                request.allowedTools(),
                request.disallowedTools(),
                request.effort()
        );
        return new ActionResult(
                result.text(),
                result.exitCode(),
                AiResponseMetadata.estimated(request.prompt(), result.text())
        );
    }

    @Override
    public String buildConversationPrompt(String systemPrompt, List<HostClaudeService.Turn> history) {
        StringBuilder sb = new StringBuilder();
        if (!history.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (HostClaudeService.Turn turn : history) {
                String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
                sb.append("[").append(label).append("]\n")
                  .append(turn.content())
                  .append("\n\n");
            }
        }
        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the system instructions.");
        return sb.toString();
    }
}
