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
    public ReasoningResult reason(ReasoningRequest request) throws IOException {
        HostClaudeService.ReasoningResult result = hostClaudeService.reasonStructured(
                request.prompt(),
                request.systemPrompt(),
                request.workingDirectory(),
                request.maxTurns(),
                request.timeoutSeconds()
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
                request.timeoutSeconds()
        );
        return new ActionResult(
                result.text(),
                result.exitCode(),
                AiResponseMetadata.fromClaudeResponse(result.parsed())
        );
    }

    @Override
    public String buildConversationPrompt(String systemPrompt, List<HostClaudeService.Turn> history) {
        return hostClaudeService.buildConversationPrompt(systemPrompt, history);
    }
}
