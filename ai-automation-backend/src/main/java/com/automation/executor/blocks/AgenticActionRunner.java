package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import com.automation.service.ai.AiProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.automation.config.ExecutionDefaults.AGENTIC_ACTION_COMPRESS_THRESHOLD;
import static com.automation.config.ExecutionDefaults.TRUNCATE_MEDIUM;
import static com.automation.executor.blocks.AgenticPromptBuilder.truncate;

/** The ACT phase: compresses oversized actions, executes them via the provider and records token metadata. */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticActionRunner {

    public record ActOutcome(String action, String output, int exitCode) {}

    private final AgenticPromptBuilder promptBuilder;
    private final AgenticEventBroadcaster events;

    public ActOutcome run(ExecutionContext context, int stepIndex, int iteration, String plannedAction,
                          AiProvider provider, String model, String effort, AgenticBlockConfig cfg,
                          AgenticTokenTracker tokenTracker, Map<String, Object> iterData) {
        String action = compressIfNeeded(context, stepIndex, iteration, plannedAction, provider, model);

        events.broadcast(context.getWorkflowRunId(), stepIndex,
                "agentic_acting", Map.of("iteration", iteration, "action", truncate(action, TRUNCATE_MEDIUM)));

        try {
            AiProvider.ActionResult actResult = provider.executeAction(
                    new AiProvider.ActionRequest(action, model,
                            context.getInstructionPrefix(), context.getProjectPath(),
                            cfg.claudeTimeout(), false, false, null, null, effort, cfg.userMcpServers()));
            String output = actResult.text() != null ? actResult.text() : "";
            tokenTracker.captureActMeta(iterData, actResult.metadata(), action, output, iteration);
            return new ActOutcome(action, output, actResult.exitCode());
        } catch (Exception e) {
            log.error("Host Claude ACT phase failed at iteration {}: {}", iteration, e.getMessage(), e);
            return new ActOutcome(action, "Execution error: " + e.getMessage(), -1);
        }
    }

    private String compressIfNeeded(ExecutionContext context, int stepIndex, int iteration,
                                    String action, AiProvider provider, String model) {
        if (action.length() <= AGENTIC_ACTION_COMPRESS_THRESHOLD) {
            return action;
        }
        log.warn("Agentic iteration {}: ACTION too long ({} chars), compressing", iteration, action.length());
        events.broadcast(context.getWorkflowRunId(), stepIndex, "agentic_action_compressed",
                Map.of("iteration", iteration, "originalLength", action.length()));
        try {
            String compressed = promptBuilder.compressAction(action, provider, model);
            log.info("Agentic iteration {}: ACTION compressed {} → {} chars", iteration, action.length(), compressed.length());
            return compressed;
        } catch (Exception e) {
            log.error("Agentic iteration {}: compression failed, truncating: {}", iteration, e.getMessage());
            return action.substring(0, AGENTIC_ACTION_COMPRESS_THRESHOLD)
                    + "\n\n[TRUNCATED: original was " + action.length() + " chars]";
        }
    }
}
