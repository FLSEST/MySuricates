package com.automation.service.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.List;

/**
 * AI provider backed by the Claude CLI on the host.
 * Thin wrapper around {@link HostClaudeService} — all capabilities supported.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ClaudeAiProvider implements AiProvider {

    /**
     * Die Claude-CLI-Version, die der Katalog des Installers nennt und das Tryout-Image installiert
     * (38f30352, wie {@link PiAiProvider#PINNED_VERSION}): eine neue Version kann Flags oder das
     * Ausgabeformat aendern, die {@link HostClaudeService} nutzt, ohne dass es auffaellt. Update-Weg:
     * docs/entwicklung.md → „Versionen der KI-CLIs“; {@code ProviderCatalogTest} haelt
     * {@code ARG CLAUDE_VERSION} in deploy/Dockerfile gleich.
     */
    public static final String PINNED_VERSION = "2.1.288";

    private final HostClaudeService hostClaudeService;

    @Override
    public String getId() {
        return "CLAUDE";
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        return AiProviderCapabilities.full();
    }

    @Override
    public ProviderSetup setup() {
        // macOS legt die Anmeldung im Schluesselbund ab; dann bleibt .claude.json mit oauthAccount
        return new ProviderSetup("claude",
                "curl -fsSL https://claude.ai/install.sh | bash -s " + PINNED_VERSION
                        + "   (or: npm install -g @anthropic-ai/claude-code@" + PINNED_VERSION + ")",
                "claude auth login",
                java.util.List.of(".claude/.credentials.json", ".claude.json:oauthAccount"),
                // claude setup-token bzw. API-Schluessel statt Anmeldedatei
                java.util.List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
                ".claude", "CLAUDE_CONFIG_DIR");
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        HostClaudeService.ReasoningResult result = hostClaudeService.reasonStructured(
                request.prompt(),
                request.systemPrompt(),
                request.model(),
                request.workingDirectory(),
                request.maxTurns(),
                request.timeoutSeconds(),
                request.effort()
        );
        return new ReasoningResult(
                result.text(),
                AiResponseMetadata.fromClaudeResponse(result.parsed())
        );
    }

    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        HostClaudeService.ActionResult result = hostClaudeService.executeAction(
                request.prompt(),
                request.model(),
                request.systemPrompt(),
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
                AiResponseMetadata.fromClaudeResponse(result.parsed())
        );
    }
}
