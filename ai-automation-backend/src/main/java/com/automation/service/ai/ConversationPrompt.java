package com.automation.service.ai;

import java.util.List;

/**
 * The flat conversation prompt an agentic block sends to a CLI without native multi-turn: the turns so
 * far and a marker for the answer. One place for all providers (TODO bd5df8d7) – Claude, pi and Codex
 * each kept a copy, and the turn type lived in {@code HostClaudeService}.
 */
public final class ConversationPrompt {

    /** One turn of the conversation; {@code role} is {@code "user"} or {@code "assistant"}. */
    public record Turn(String role, String content) {}

    private ConversationPrompt() {
    }

    /** The turns and the answer marker; the system prompt travels separately ({@code --system-prompt}). */
    public static String build(List<Turn> history) {
        StringBuilder sb = new StringBuilder();
        appendHistory(sb, history);
        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the system instructions.");
        return sb.toString();
    }

    /** For a CLI without a system prompt flag (Codex): the instructions go in front of the turns. */
    public static String withInstructions(String systemPrompt, List<Turn> history) {
        StringBuilder sb = new StringBuilder();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append("=== INSTRUCTIONS ===\n");
            sb.append(systemPrompt);
            sb.append("\n\n");
        }
        appendHistory(sb, history);
        sb.append("=== YOUR RESPONSE ===\n");
        sb.append("Respond following the format specified in the instructions above.");
        return sb.toString();
    }

    private static void appendHistory(StringBuilder sb, List<Turn> history) {
        if (history.isEmpty()) {
            return;
        }
        sb.append("=== CONVERSATION HISTORY ===\n");
        for (Turn turn : history) {
            String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
            sb.append("[").append(label).append("]\n");
            sb.append(turn.content());
            sb.append("\n\n");
        }
    }
}
