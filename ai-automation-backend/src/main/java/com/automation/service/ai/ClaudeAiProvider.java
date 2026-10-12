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
     * The Claude CLI version named by the installer's catalog and installed by the tryout image
     * (38f30352, like {@link PiAiProvider#PINNED_VERSION}): a new version can change flags or the
     * output format used by {@link HostClaudeService} without anyone noticing. Update path:
     * docs/entwicklung.md → "Versionen der KI-CLIs"; {@code ProviderCatalogTest} keeps
     * {@code ARG CLAUDE_VERSION} in deploy/Dockerfile equal.
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
        // macOS keeps the login in the keychain; in that case .claude.json with oauthAccount remains
        return new ProviderSetup("claude",
                "curl -fsSL https://claude.ai/install.sh | bash -s " + PINNED_VERSION
                        + "   (or: npm install -g @anthropic-ai/claude-code@" + PINNED_VERSION + ")",
                "claude auth login",
                java.util.List.of(".claude/.credentials.json", ".claude.json:oauthAccount"),
                // claude setup-token or API key instead of a login file
                java.util.List.of("CLAUDE_CODE_OAUTH_TOKEN", "ANTHROPIC_API_KEY"),
                ".claude", "CLAUDE_CONFIG_DIR");
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        HostClaudeService.ReasoningResult result = hostClaudeService.reasonStructured(request);
        return new ReasoningResult(
                result.text(),
                AiResponseMetadata.fromClaudeResponse(result.parsed())
        );
    }

    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        HostClaudeService.ActionResult result = hostClaudeService.executeAction(request);
        return new ActionResult(
                result.text(),
                result.exitCode(),
                AiResponseMetadata.fromClaudeResponse(result.parsed())
        );
    }
}
