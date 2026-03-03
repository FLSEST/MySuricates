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
 * no token tracking (estimated), no model selection.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CodexAiProvider implements AiProvider {

    private final HostCodexService hostCodexService;
    private final SummarizationConfig summarizationConfig;

    @Override
    public String getId() {
        return "CODEX";
    }

    @Override
    public AiProviderCapabilities getCapabilities() {
        return AiProviderCapabilities.minimal();
    }

    @Override
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        // Merge system prompt into user prompt since Codex doesn't support --system-prompt
        String fullPrompt = mergeSystemPrompt(request.systemPrompt(), request.prompt());
        Path workDir = request.workingDirectory() != null ? Path.of(request.workingDirectory()) : null;
        int timeout = request.timeoutSeconds() != null
                ? request.timeoutSeconds()
                : summarizationConfig.getTimeoutSeconds();

        String output = hostCodexService.execute(fullPrompt, workDir, timeout);

        return new ReasoningResult(
                output,
                AiResponseMetadata.estimated(fullPrompt, output)
        );
    }

    @Override
    public ActionResult executeAction(ActionRequest request) throws IOException {
        // Merge system prompt into action prompt
        String fullPrompt = mergeSystemPrompt(request.systemPrompt(), request.prompt());
        Path workDir = request.workingDirectory() != null ? Path.of(request.workingDirectory()) : null;
        int timeout = request.timeoutSeconds() != null
                ? request.timeoutSeconds()
                : summarizationConfig.getTimeoutSeconds();

        if (!getCapabilities().supportsAllowedTools()) {
            log.warn("Codex provider does not support --allowedTools; action runs without tool restrictions");
        }

        try {
            String output = hostCodexService.execute(fullPrompt, workDir, timeout);
            return new ActionResult(output, 0, AiResponseMetadata.estimated(fullPrompt, output));
        } catch (IOException e) {
            // Codex CLI exit code > 0 throws IOException — map to non-zero exit
            String errorMsg = e.getMessage() != null ? e.getMessage() : "Codex execution failed";
            return new ActionResult(errorMsg, 1, AiResponseMetadata.estimated(fullPrompt, errorMsg));
        }
    }

    @Override
    public String buildConversationPrompt(String systemPrompt, List<HostClaudeService.Turn> history) {
        // Codex has no multi-turn: flatten everything into a single prompt
        StringBuilder sb = new StringBuilder();

        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("=== INSTRUCTIONS ===\n");
            sb.append(systemPrompt);
            sb.append("\n\n");
        }

        if (!history.isEmpty()) {
            sb.append("=== CONVERSATION HISTORY ===\n");
            for (HostClaudeService.Turn turn : history) {
                String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
                sb.append("[").append(label).append("]\n");
                sb.append(turn.content());
                sb.append("\n\n");
            }
        }

        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the instructions above.");

        return sb.toString();
    }

    private String mergeSystemPrompt(String systemPrompt, String userPrompt) {
        if (systemPrompt == null || systemPrompt.isBlank()) {
            return userPrompt;
        }
        return "=== SYSTEM INSTRUCTIONS ===\n" + systemPrompt +
                "\n\n=== TASK ===\n" + userPrompt;
    }
}
