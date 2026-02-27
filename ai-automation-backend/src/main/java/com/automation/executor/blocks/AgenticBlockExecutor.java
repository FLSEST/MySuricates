package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ReasoningResult;
import com.automation.service.ai.HostClaudeService.Turn;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.learning.LearningService;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticBlockExecutor implements BlockExecutor {

    private final HostClaudeService hostClaude;
    private final CommandExecutorService commandExecutor;
    private final LearningService learningService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowRunRepository runRepository;

    @Override
    public boolean supports(String blockType) {
        return "agentic".equals(blockType);
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();
        Map<String, Object> config = block.getConfig();

        // Extract config
        String goal = context.resolveTemplate(requireString(config, "goal"));
        String successCriteria = context.resolveTemplate(
                (String) config.getOrDefault("success_criteria", ""));
        int maxIterations = ((Number) config.getOrDefault("max_iterations", 10)).intValue();
        int iterationTimeoutSeconds = ((Number) config.getOrDefault(
                "iteration_timeout_seconds", 300)).intValue();
        int totalTimeoutSeconds = ((Number) config.getOrDefault(
                "total_timeout_seconds", 1800)).intValue();
        int maxTotalTokens = ((Number) config.getOrDefault(
                "max_total_tokens", 200_000)).intValue();
        int maxRepeatedActions = ((Number) config.getOrDefault(
                "max_repeated_actions", 3)).intValue();
        boolean requireApproval = Boolean.TRUE.equals(config.get("require_approval"));
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        // Gather learning context — resolve workflow ID from the run
        String workflowId = runRepository.findById(context.getWorkflowRunId())
                .map(run -> run.getWorkflow().getId())
                .orElse(null);
        String learningContext = buildLearningContext(workflowId);

        // Build system prompt for host Claude (include workflow instructions for consistency)
        String workflowInstructions = context.getInstructionPrefix();
        String systemPrompt = buildSystemPrompt(goal, successCriteria, learningContext, workflowInstructions);

        // Conversation history for multi-turn reasoning
        List<Turn> history = new ArrayList<>();
        List<Map<String, Object>> iterationLog = new ArrayList<>();

        // Initial user turn
        history.add(new Turn("user",
                "Begin working on the goal. Analyze what needs to be done and propose your first action."));

        // Broadcast start
        broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                "agentic_started", Map.of("goal", goal, "maxIterations", maxIterations));

        StringBuilder fullOutput = new StringBuilder();
        int iteration = 0;
        boolean goalAchieved = false;
        boolean approvalRequired = requireApproval;
        Instant blockStart = Instant.now();
        int runningTotalTokens = 0;
        int consecutiveBlindIterations = 0;
        boolean usingFallbackEstimation = false;
        List<String> recentActions = new ArrayList<>();
        String stopReason = null;

        while (iteration < maxIterations && !context.shouldStop()) {
            // Overall timeout check
            long elapsedSeconds = (Instant.now().toEpochMilli() - blockStart.toEpochMilli()) / 1000;
            if (elapsedSeconds > totalTimeoutSeconds) {
                stopReason = "TOTAL_TIMEOUT";
                fullOutput.append("[TOTAL TIMEOUT after ").append(elapsedSeconds).append("s]\n");
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_total_timeout", Map.of("elapsedSeconds", elapsedSeconds));
                break;
            }
            // Handle pause
            while (context.isPaused() && !context.shouldStop()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (context.shouldStop()) break;

            iteration++;
            Instant iterStart = Instant.now();
            Map<String, Object> iterData = new LinkedHashMap<>();
            iterData.put("iteration", iteration);

            // === REASON phase ===
            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_reasoning", Map.of("iteration", iteration));

            String reasoning;
            try {
                String conversationPrompt = hostClaude.buildConversationPrompt(systemPrompt, history);
                ReasoningResult reasonResult = hostClaude.reasonStructured(
                        conversationPrompt, systemPrompt, null, null, claudeTimeout);
                reasoning = reasonResult.text();

                // Capture reasoning token/cost metadata
                if (reasonResult.parsed() != null && reasonResult.parsed().usage() != null) {
                    Map<String, Object> reasonMeta = reasonResult.parsed().toMetadata();
                    iterData.put("reasonMeta", reasonMeta);
                } else {
                    // Fallback: estimate tokens from string length (~4 chars/token)
                    int estimatedInput = conversationPrompt.length() / 4;
                    int estimatedOutput = reasoning.length() / 4;
                    Map<String, Object> fallbackMeta = Map.of(
                            "inputTokens", estimatedInput,
                            "outputTokens", estimatedOutput,
                            "estimated", true);
                    iterData.put("reasonMeta", fallbackMeta);
                    log.warn("Agentic iteration {}: REASON phase returned no usage data — using fallback estimation ({} in, {} out)",
                            iteration, estimatedInput, estimatedOutput);
                }
            } catch (Exception e) {
                log.error("Host Claude reasoning failed at iteration {}: {}", iteration, e.getMessage());
                iterData.put("phase", "reason");
                iterData.put("error", e.getMessage());
                iterationLog.add(iterData);
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_error", Map.of("iteration", iteration,
                                "phase", "reason", "error", e.getMessage()));
                fullOutput.append("[iteration ").append(iteration)
                        .append(" - REASON ERROR]: ").append(e.getMessage()).append("\n");
                break;
            }

            // Add assistant response to history
            history.add(new Turn("assistant", reasoning));
            iterData.put("reasoning", truncate(reasoning, 2000));

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_reasoned", Map.of("iteration", iteration,
                            "reasoning", truncate(reasoning, 2000)));

            // Parse structured response
            AgenticPlan plan = AgenticPlan.parse(reasoning);

            if (plan.isGoalComplete()) {
                goalAchieved = "ACHIEVED".equalsIgnoreCase(plan.getGoalStatus());
                fullOutput.append("[iteration ").append(iteration).append(" - ")
                        .append(plan.getGoalStatus()).append("]: ")
                        .append(plan.getSummary()).append("\n");
                iterData.put("result", plan.getGoalStatus().toLowerCase());
                iterData.put("summary", plan.getSummary());
                iterationLog.add(iterData);
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        goalAchieved ? "agentic_goal_achieved" : "agentic_goal_impossible",
                        Map.of("iteration", iteration, "summary", plan.getSummary()));
                break;
            }

            // === Optional APPROVAL gate ===
            if (approvalRequired && !plan.getAction().isEmpty()) {
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_approval_required",
                        Map.of("iteration", iteration,
                                "action", plan.getAction(),
                                "rationale", truncate(plan.getRationale(), 500)));

                CompletableFuture<String> future = context.requestApproval(
                        ApprovalRequest.builder()
                                .blockId("agentic-" + stepIndex + "-iter-" + iteration)
                                .question("Approve action: " + truncate(plan.getAction(), 200))
                                .options(List.of("Yes", "Yes always", "No"))
                                .stepIndex(stepIndex)
                                .build());

                String answer;
                try {
                    answer = future.get(30, TimeUnit.MINUTES);
                } catch (Exception e) {
                    iterData.put("result", "approval_timeout");
                    iterationLog.add(iterData);
                    fullOutput.append("[iteration ").append(iteration)
                            .append(" - APPROVAL TIMEOUT]\n");
                    break;
                }

                if ("No".equalsIgnoreCase(answer)) {
                    history.add(new Turn("user",
                            "The user rejected this action. Propose an alternative approach."));
                    iterData.put("approval", "rejected");
                    iterationLog.add(iterData);
                    fullOutput.append("[iteration ").append(iteration)
                            .append(" - REJECTED]: ").append(plan.getAction()).append("\n");
                    continue;
                }
                if ("Yes always".equalsIgnoreCase(answer)) {
                    approvalRequired = false;
                }
                iterData.put("approval", "approved");
            }

            // === ACT phase ===
            if (plan.getAction().isEmpty()) {
                history.add(new Turn("user",
                        "Your response did not contain a clear action. Please specify an ACTION to execute."));
                iterData.put("result", "no_action");
                iterationLog.add(iterData);
                continue;
            }

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_acting", Map.of("iteration", iteration,
                            "action", truncate(plan.getAction(), 500)));

            String actionOutput;
            int exitCode;
            try {
                String actionCommand = plan.getAction();
                String containerSystemPrompt = context.getInstructionPrefix();
                CliSession.EnrichedCommandResult cmdResult = commandExecutor.executeCommandOnHost(
                        context.getProjectPath(), actionCommand, context.getModel(), containerSystemPrompt,
                        claudeTimeout);
                actionOutput = cmdResult.output() != null ? cmdResult.output() : "";
                exitCode = cmdResult.exitCode();

                // Capture action token/cost metadata
                if (cmdResult.parsed() != null && cmdResult.parsed().usage() != null) {
                    Map<String, Object> actMeta = cmdResult.parsed().toMetadata();
                    iterData.put("actMeta", actMeta);
                } else {
                    // Fallback: estimate tokens from string length (~4 chars/token)
                    int estimatedInput = actionCommand.length() / 4;
                    int estimatedOutput = actionOutput.length() / 4;
                    Map<String, Object> fallbackMeta = Map.of(
                            "inputTokens", estimatedInput,
                            "outputTokens", estimatedOutput,
                            "estimated", true);
                    iterData.put("actMeta", fallbackMeta);
                    log.warn("Agentic iteration {}: ACT phase returned no usage data — using fallback estimation ({} in, {} out)",
                            iteration, estimatedInput, estimatedOutput);
                }
            } catch (Exception e) {
                actionOutput = "Execution error: " + e.getMessage();
                exitCode = -1;
            }

            // Track tokens for budget check (includes fallback estimates)
            runningTotalTokens += extractTokenCount(iterData, "reasonMeta", "inputTokens");
            runningTotalTokens += extractTokenCount(iterData, "reasonMeta", "outputTokens");
            runningTotalTokens += extractTokenCount(iterData, "actMeta", "inputTokens");
            runningTotalTokens += extractTokenCount(iterData, "actMeta", "outputTokens");

            // Track consecutive iterations without real usage data
            boolean reasonEstimated = isEstimated(iterData, "reasonMeta");
            boolean actEstimated = isEstimated(iterData, "actMeta");
            if (reasonEstimated && actEstimated) {
                consecutiveBlindIterations++;
                usingFallbackEstimation = true;
            } else {
                consecutiveBlindIterations = 0;
            }

            // Abort if too many consecutive iterations had no real usage data
            if (consecutiveBlindIterations >= 3) {
                stopReason = "TOKEN_TRACKING_FAILED";
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_token_tracking_failed",
                        Map.of("iteration", iteration, "consecutiveBlind", consecutiveBlindIterations));
                fullOutput.append("[TOKEN TRACKING FAILED: ").append(consecutiveBlindIterations)
                        .append(" consecutive iterations without real usage data — using fallback estimates, budget enforcement is approximate]\n");
                log.error("Agentic block aborting after {} consecutive iterations without real token usage data", consecutiveBlindIterations);
                break;
            }

            iterData.put("action", plan.getAction());
            iterData.put("actionOutput", truncate(actionOutput, 2000));
            iterData.put("exitCode", exitCode);

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_acted", Map.of("iteration", iteration,
                            "action", truncate(plan.getAction(), 500),
                            "output", truncate(actionOutput, 2000),
                            "exitCode", exitCode));

            // === OBSERVE phase ===
            String observation = "Action result (exit code " + exitCode + "):\n" + actionOutput;
            history.add(new Turn("user",
                    "Observation from executing your action:\n" + truncate(observation, 4000)
                            + "\n\nAnalyze this result. Has the goal been achieved? "
                            + "If not, what should we do next?"));

            context.setPreviousOutput(actionOutput);

            fullOutput.append("[iteration ").append(iteration).append("]\n")
                    .append("  Reasoning: ").append(truncate(plan.getRationale(), 200)).append("\n")
                    .append("  Action: ").append(truncate(plan.getAction(), 200)).append("\n")
                    .append("  Exit code: ").append(exitCode).append("\n")
                    .append("  Result: ").append(truncate(actionOutput, 500)).append("\n\n");

            long iterMs = Instant.now().toEpochMilli() - iterStart.toEpochMilli();
            iterData.put("durationMs", iterMs);
            iterationLog.add(iterData);

            // Timeout check per iteration
            if (iterMs > (long) iterationTimeoutSeconds * 1000) {
                stopReason = "ITERATION_TIMEOUT";
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_iteration_timeout", Map.of("iteration", iteration));
                fullOutput.append("[iteration ").append(iteration)
                        .append(" - TIMEOUT]\n");
                break;
            }

            // Token budget check
            if (maxTotalTokens > 0 && runningTotalTokens > maxTotalTokens) {
                stopReason = "TOKEN_BUDGET_EXCEEDED";
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_token_budget_exceeded",
                        Map.of("iteration", iteration, "totalTokens", runningTotalTokens,
                                "budget", maxTotalTokens));
                fullOutput.append("[TOKEN BUDGET EXCEEDED: ").append(runningTotalTokens)
                        .append("/").append(maxTotalTokens).append(" tokens]\n");
                break;
            }

            // Progress detection — abort if same action repeated N times
            String actionNormalized = plan.getAction().strip();
            recentActions.add(actionNormalized);
            if (recentActions.size() >= maxRepeatedActions) {
                List<String> lastN = recentActions.subList(
                        recentActions.size() - maxRepeatedActions, recentActions.size());
                if (lastN.stream().allMatch(a -> a.equals(lastN.getFirst()))) {
                    stopReason = "REPEATED_ACTIONS";
                    broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                            "agentic_repeated_actions",
                            Map.of("iteration", iteration, "repeatedCount", maxRepeatedActions));
                    fullOutput.append("[ABORTED: Same action repeated ")
                            .append(maxRepeatedActions).append(" times]\n");
                    break;
                }
            }

            // History compression — keep recent turns in full, summarize older ones
            compressHistory(history);
        }

        // Finalize
        Instant end = Instant.now();
        StepResult result = StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Agentic] " + goal)
                .output(fullOutput.toString().trim())
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .status(goalAchieved ? StepStatus.COMPLETED : StepStatus.FAILED)
                .build();

        // Aggregate token/cost data across all iterations
        int totalInputTokens = 0;
        int totalOutputTokens = 0;
        double totalCostUsd = 0.0;
        List<String> allToolNames = new ArrayList<>();

        for (Map<String, Object> iter : iterationLog) {
            totalInputTokens += extractTokenCount(iter, "reasonMeta", "inputTokens");
            totalInputTokens += extractTokenCount(iter, "actMeta", "inputTokens");
            totalOutputTokens += extractTokenCount(iter, "reasonMeta", "outputTokens");
            totalOutputTokens += extractTokenCount(iter, "actMeta", "outputTokens");
            totalCostUsd += extractCost(iter, "reasonMeta");
            totalCostUsd += extractCost(iter, "actMeta");
            allToolNames.addAll(extractToolNames(iter, "actMeta"));
        }

        // Store iteration details in metadata for learning
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goal", goal);
        metadata.put("successCriteria", successCriteria);
        metadata.put("totalIterations", iteration);
        metadata.put("goalAchieved", goalAchieved);
        metadata.put("inputTokens", totalInputTokens);
        metadata.put("outputTokens", totalOutputTokens);
        metadata.put("costUsd", totalCostUsd);
        if (!allToolNames.isEmpty()) {
            metadata.put("toolNames", allToolNames);
            metadata.put("toolUseCount", allToolNames.size());
        }
        metadata.put("totalTokensUsed", runningTotalTokens);
        if (usingFallbackEstimation) {
            metadata.put("tokenEstimationFallback", true);
        }
        if (stopReason != null) {
            metadata.put("stopReason", stopReason);
        }
        metadata.put("iterations", iterationLog);
        result.setMetadata(metadata);

        // Set context variables for downstream blocks
        context.setVariable("agentic_goal_achieved", goalAchieved);
        context.setVariable("agentic_iterations", iteration);

        broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                "agentic_completed", Map.of(
                        "goalAchieved", goalAchieved,
                        "totalIterations", iteration,
                        "durationMs", result.getDurationMs()));

        return result;
    }

    /**
     * Compress conversation history to manage context window size.
     * Keeps the first turn (initial prompt) and the most recent 4 turns in full.
     * Middle turns are collapsed into a single summary turn.
     */
    private void compressHistory(List<Turn> history) {
        int keepRecentCount = 4;
        // Only compress if history is long enough to benefit
        // Need at least: 1 (initial) + keepRecentCount + 2 (to compress at least 2 turns)
        if (history.size() <= keepRecentCount + 3) {
            return;
        }

        // Keep index 0 (initial user prompt) and the last keepRecentCount turns
        int compressEnd = history.size() - keepRecentCount;

        // Build summary of middle turns (from index 1 to compressEnd-1)
        StringBuilder summary = new StringBuilder();
        summary.append("[Compressed history of ").append(compressEnd - 1).append(" turns]\n");
        for (int i = 1; i < compressEnd; i++) {
            Turn turn = history.get(i);
            String label = "assistant".equals(turn.role()) ? "ASSISTANT" : "USER";
            summary.append("- ").append(label).append(": ").append(truncate(turn.content(), 150)).append("\n");
        }

        // Remove the middle turns and replace with summary
        List<Turn> compressed = new ArrayList<>();
        compressed.add(history.get(0)); // keep initial prompt
        compressed.add(new Turn("user", summary.toString())); // compressed middle
        compressed.addAll(history.subList(compressEnd, history.size())); // keep recent

        history.clear();
        history.addAll(compressed);
    }

    private String buildLearningContext(String workflowId) {
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
                sb.append("- Goal: ").append(entry.get("goal"))
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

    private String buildSystemPrompt(String goal, String successCriteria,
                                      String learningContext, String workflowInstructions) {
        String instructionsSection = "";
        if (workflowInstructions != null && !workflowInstructions.isBlank()) {
            instructionsSection = "\n## Workflow Instructions\n" +
                    "The following instructions apply to this workflow and should guide your planning:\n" +
                    workflowInstructions + "\n";
        }

        return """
                You are an autonomous agent orchestrator. You analyze situations, plan actions, and evaluate results.
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
                %s""".formatted(goal, successCriteria, instructionsSection, learningContext);
    }

    private void broadcastAgenticEvent(String runId, int stepIndex,
                                       String eventType, Map<String, Object> data) {
        try {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", eventType);
            message.put("stepIndex", stepIndex);
            message.putAll(data);
            messagingTemplate.convertAndSend(
                    "/topic/executions/" + runId + "/logs", message);
        } catch (Exception e) {
            log.warn("Failed to broadcast agentic event: {}", e.getMessage());
        }
    }

    private String requireString(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) {
            throw new WorkflowExecutionException("Agentic block requires '" + key + "' in config");
        }
        return value.toString();
    }

    @SuppressWarnings("unchecked")
    private boolean isEstimated(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            return Boolean.TRUE.equals(((Map<String, Object>) map).get("estimated"));
        }
        return true; // no metadata at all counts as estimated
    }

    @SuppressWarnings("unchecked")
    private int extractTokenCount(Map<String, Object> iterData, String metaKey, String field) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get(field);
            if (val instanceof Number n) return n.intValue();
        }
        return 0;
    }

    @SuppressWarnings("unchecked")
    private double extractCost(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get("costUsd");
            if (val instanceof Number n) return n.doubleValue();
        }
        return 0.0;
    }

    @SuppressWarnings("unchecked")
    private List<String> extractToolNames(Map<String, Object> iterData, String metaKey) {
        Object meta = iterData.get(metaKey);
        if (meta instanceof Map<?, ?> map) {
            Object val = ((Map<String, Object>) map).get("toolNames");
            if (val instanceof List<?> list) {
                return list.stream().map(Object::toString).toList();
            }
        }
        return List.of();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
