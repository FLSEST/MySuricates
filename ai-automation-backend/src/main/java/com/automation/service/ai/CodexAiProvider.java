package com.automation.service.ai;

import com.automation.config.SummarizationConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * AI provider backed by the OpenAI Codex CLI on the host.
 * Adapts limitations: no system prompt (merged into user prompt),
 * no token tracking (estimated), no effort flag. The model is passed via {@code -m};
 * available slugs come from {@code CodexModelCatalogSource}. Codex does not load
 * {@code .claude/rules/}, so skills, workflow memory and project context are merged into the prompt
 * ({@link RunContextFiles}).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexAiProvider implements AiProvider {

    /**
     * The Codex CLI version named by the installer's catalog and installed by the tryout image
     * (38f30352, like {@link PiAiProvider#PINNED_VERSION}): a new version can break the calls of
     * {@link HostCodexService} ({@code exec}, {@code -m}, sandbox flags). Update path:
     * docs/entwicklung.md → "Versionen der KI-CLIs"; {@code ProviderCatalogTest} keeps
     * {@code ARG CODEX_VERSION} in deploy/Dockerfile equal.
     */
    public static final String PINNED_VERSION = "0.154.0";

    private final HostCodexService hostCodexService;
    private final SummarizationConfig summarizationConfig;

    @Override
    public String getId() {
        return "CODEX";
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        return AiProviderCapabilities.codex();
    }

    @Override
    public ProviderSetup setup() {
        return new ProviderSetup("codex", "npm install -g @openai/codex@" + PINNED_VERSION, "codex login",
                java.util.List.of(".codex/auth.json"),
                java.util.List.of("OPENAI_API_KEY", "CODEX_API_KEY"),
                ".codex", "CODEX_HOME");
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        // Merge system prompt into user prompt since Codex doesn't support --system-prompt
        // Reasoning is always read-only (analysis only, no file writes)
        String fullPrompt = mergeSystemPrompt(
                RunContextFiles.withRunContext(request.systemPrompt(), request.workingDirectory()), request.prompt(), true);
        Path workDir = request.workingDirectory() != null ? Path.of(request.workingDirectory()) : null;
        int timeout = request.timeoutSeconds() != null
                ? request.timeoutSeconds()
                : summarizationConfig.getTimeoutSeconds();

        String output = hostCodexService.execute(fullPrompt, workDir, timeout, true, request.model(), false);

        return new ReasoningResult(
                output,
                AiResponseMetadata.estimated(fullPrompt, output)
        );
    }

    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        if (request.effort() != null && !request.effort().isBlank()) {
            log.debug("Codex provider ignores effort '{}' (no CLI reasoning flag)", request.effort());
        }
        boolean readOnly = request.readOnly();
        String fullPrompt = mergeSystemPrompt(
                RunContextFiles.withRunContext(request.systemPrompt(), request.workingDirectory()), request.prompt(), readOnly);
        Path workDir = request.workingDirectory() != null ? Path.of(request.workingDirectory()) : null;
        int timeout = request.timeoutSeconds() != null
                ? request.timeoutSeconds()
                : summarizationConfig.getTimeoutSeconds();

        if (!readOnly && !getCapabilities().supportsAllowedTools()) {
            log.warn("Codex provider does not support --allowedTools; action runs without tool restrictions");
        }

        try {
            String output = hostCodexService.execute(fullPrompt, workDir, timeout, readOnly, request.model(), request.priority());
            return new ActionResult(output, 0, AiResponseMetadata.estimated(fullPrompt, output));
        } catch (IOException e) {
            // Codex CLI exit code > 0 throws IOException — map to non-zero exit
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Codex execution failed";
            return new ActionResult(errorMsg, 1, AiResponseMetadata.estimated(fullPrompt, errorMsg));
        }
    }

    @Override
    public String buildConversationPrompt(String systemPrompt, List<ConversationPrompt.Turn> history) {
        // Codex has no multi-turn and no system prompt flag: everything goes into one prompt
        return ConversationPrompt.withInstructions(systemPrompt, history);
    }

    private String mergeSystemPrompt(String systemPrompt, String userPrompt, boolean readOnly) {
        StringBuilder sb = new StringBuilder();

        if (readOnly) {
            sb.append("Output your entire response as direct text. Do NOT write any files.\n\n");
        }

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("=== SYSTEM INSTRUCTIONS ===\n");
            sb.append(systemPrompt);
            sb.append("\n\n");
        }

        sb.append("=== TASK ===\n");
        sb.append(userPrompt);
        return sb.toString();
    }
}
