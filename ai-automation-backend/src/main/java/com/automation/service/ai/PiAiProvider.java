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
     * The pi version installed by the installer ({@code --with-pi}) and the tryout image (c3a4cbf6).
     * Pinned because a new pi version can break the calls of this class ({@code --thinking},
     * {@code <backend>/<model>}) without anyone noticing. Update path: docs/entwicklung.md →
     * "Versionen der KI-CLIs"; {@code ProviderCatalogTest} keeps {@code ARG PI_VERSION} in deploy/Dockerfile equal.
     */
    public static final String PINNED_VERSION = "0.74.2";

    /** "dann /login in pi — oder ein API-Schluessel als Umgebungsvariable", translated per UI. */
    static final String LOGIN_NOTE = "pi_slash_login";

    private final HostPiService hostPiService;

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public ProviderSetup setup() {
        // pi is a hub itself: whoever has at least one backend in auth.json is logged in — a
        // quotation mark means "at least one key", an empty {} does not count.
        // Login happens in pi itself (/login) — that is what the LOGIN_NOTE hint says, translated (eab43a8b)
        return new ProviderSetup("pi", "npm install -g @earendil-works/pi-coding-agent@" + PINNED_VERSION,
                "pi",
                java.util.List.of(".pi/agent/auth.json:\""),
                // the common backends from "pi --help" (Environment Variables); any key
                // suffices because pi chooses the backend per model (<backend>/<model>)
                java.util.List.of("ANTHROPIC_API_KEY", "ANTHROPIC_OAUTH_TOKEN", "OPENAI_API_KEY",
                        "GEMINI_API_KEY", "OPENROUTER_API_KEY"),
                ".pi/agent", "PI_CODING_AGENT_DIR", LOGIN_NOTE);
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
                AiResponseMetadata.estimated(request.prompt(), result.text()),
                result.thinkingLevel()
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
}
