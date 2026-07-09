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
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class PiAiProvider implements AiProvider {

    public static final String ID = "PI";

    private final HostPiService hostPiService;

    @Override
    public String getId() {
        return ID;
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
                request.systemPrompt(),
                null,
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
