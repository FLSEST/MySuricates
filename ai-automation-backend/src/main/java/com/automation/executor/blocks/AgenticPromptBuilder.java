package com.automation.executor.blocks;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.HostClaudeService.Turn;
import com.automation.service.learning.LearningService;
import com.automation.service.system.SystemIdentityService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static com.automation.config.ExecutionDefaults.AGENTIC_ACTION_COMPRESS_TIMEOUT;
import static com.automation.config.ExecutionDefaults.TRUNCATE_HISTORY;

/**
 * Prompt and context construction for the agentic Reason-Act-Observe loop: the reasoning system
 * prompt, the "previously tried strategies" learning context, conversation-history compression
 * and compression of oversized actions.
 *
 * <p>Extracted from {@code AgenticBlockExecutor} (Audit-TODO a253ad3e). Runs no loop and holds
 * no state.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticPromptBuilder {

    private static final String ACTION_COMPRESS_PROMPT = """
            You are a task compression assistant. The following is an oversized action description \
            from a workflow automation system. It contains actionable instructions mixed with tool output, \
            file contents, and diagnostic data.

            Extract ONLY the actionable instructions — what needs to be done, where (file paths, \
            commands), and specific details (values, patterns, names). Discard all tool output, \
            file contents, stack traces, and verbose diagnostic text.

            Output a clean, concise action description under 50000 characters. Preserve all file paths, \
            command names, variable values, and specific technical details needed to execute the action.

            === OVERSIZED ACTION ===
            """;

    private final LearningService learningService;
    private final SystemIdentityService systemIdentity;

    /**
     * Compress conversation history to manage context window size.
     * Keeps the first turn (initial prompt) and the most recent 4 turns in full.
     * Middle turns are collapsed into a single summary turn.
     */
    void compressHistory(List<Turn> history) {
        int keepRecentCount = 4;
        if (history.size() <= keepRecentCount + 3) {
            return;
        }

        int compressEnd = history.size() - keepRecentCount;

        StringBuilder summary = new StringBuilder();
        summary.append("[Compressed history of ").append(compressEnd - 1).append(" turns]\n");
        for (int i = 1; i < compressEnd; i++) {
            Turn turn = history.get(i);
            String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
            summary.append("- ").append(label).append(": ").append(truncate(turn.content(), TRUNCATE_HISTORY)).append("\n");
        }

        List<Turn> compressed = new ArrayList<>();
        compressed.add(history.get(0));
        compressed.add(new Turn("user", summary.toString()));
        compressed.addAll(history.subList(compressEnd, history.size()));

        history.clear();
        history.addAll(compressed);
    }

    String compressAction(String oversizedAction, AiProvider provider, String model) throws IOException {
        String prompt = ACTION_COMPRESS_PROMPT + oversizedAction;
        AiProvider.ReasoningResult result = provider.reason(
                new AiProvider.ReasoningRequest(prompt, null, model, null, 1, AGENTIC_ACTION_COMPRESS_TIMEOUT, null));
        String compressed = result.text();
        if (compressed == null || compressed.isBlank()) {
            throw new IOException("Action compression returned empty result");
        }
        return compressed;
    }

    String buildLearningContext(String workflowId) {
        if (workflowId == null) {
            return "";
        }
        try {
            List<Map<String, Object>> agenticHistory =
                    learningService.getAgenticStrategyHistory(workflowId);

            if (agenticHistory.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n## Previously Tried Strategies (from past runs)\n");
            for (Map<String, Object> entry : agenticHistory) {
                sb.append("- Goal: ").append(truncate(entry.get("goal").toString(), 500))
                        .append(" | Iterations: ").append(entry.get("totalIterations"))
                        .append(" | Outcome: ").append(entry.get("outcome")).append("\n");
                Object strategy = entry.get("strategy");
                if (strategy != null && !strategy.toString().isEmpty()) {
                    sb.append("  Strategies: ").append(truncate(strategy.toString(), 300)).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            log.debug("Failed to build learning context: {}", e.getMessage());
            return "";
        }
    }

    String buildSystemPrompt(String goal, String successCriteria,
                             String learningContext, String workflowInstructions) {
        String instructionsSection = "";
        if (workflowInstructions != null && !workflowInstructions.isBlank()) {
            instructionsSection = "\n## Workflow Instructions\n" +
                    "The following instructions apply to this workflow and should guide your planning:\n" +
                    workflowInstructions + "\n";
        }

        return """
                You are the reasoning engine of %s, an AI workflow automation platform. \
                You analyze situations, plan actions, and evaluate results.
                You operate in a Reason-Act-Observe loop. The actions you plan will be executed by another AI agent \
                inside a Docker container that has access to files, tools, and a development environment.

                ## Your Goal
                %s

                ## Success Criteria
                %s
                %s\
                ## Response Format
                You MUST respond in this exact format:

                GOAL_STATUS: IN_PROGRESS
                RATIONALE: <your reasoning about the current state and what to do next>
                ACTION: <the exact instruction/task to give to the executor agent in the container>
                SUMMARY: <brief summary of what this action will accomplish>

                When the goal is achieved, respond with:
                GOAL_STATUS: ACHIEVED
                RATIONALE: <why the goal is now met>
                ACTION: <empty or final verification>
                SUMMARY: <what was accomplished>

                If the goal is impossible, respond with:
                GOAL_STATUS: IMPOSSIBLE
                RATIONALE: <why the goal cannot be achieved>
                ACTION:
                SUMMARY: <what was tried and why it failed>

                ## Guidelines
                - The ACTION will be sent as a task description to Claude CLI running inside a Docker container.
                - The container agent can run commands, edit files, and interact with the development environment.
                - Be specific in your ACTION -- describe exactly what needs to be done.
                - If a previous action failed, analyze the error and try a different approach.
                - Do NOT repeat the same action if it failed -- try a different approach.
                - Prefer small, incremental steps over large changes.
                - After each observation, assess progress towards the goal.
                %s""".formatted(systemIdentity.getVersionedName(), goal, successCriteria, instructionsSection, learningContext);
    }

    /** Agentic-style truncation: cut at {@code maxLen} and append {@code ...}; {@code null} → empty. */
    static String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
