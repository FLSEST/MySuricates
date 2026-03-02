package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.ai.HostClaudeService.ReasoningResult;
import com.automation.service.ai.HostClaudeService.Turn;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.learning.LearningService;
import com.automation.service.system.SystemIdentityService;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.automation.config.ExecutionDefaults.*;

@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticBlockExecutor implements BlockExecutor {

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

    private final HostClaudeService hostClaude;
    private final CommandExecutorService commandExecutor;
    private final LearningService learningService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WorkflowRunRepository runRepository;
    private final SystemIdentityService systemIdentity;

    @Override
    public boolean supports(String blockType) {
        return "agentic".equals(blockType);
    }

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        int stepIndex = context.nextStepIndex();
        Instant start = Instant.now();

        // Parse and validate config
        AgenticBlockConfig cfg = AgenticBlockConfig.from(block.getConfig(), context);

        // Gather learning context
        String workflowId = runRepository.findById(context.getWorkflowRunId())
                .map(run -> run.getWorkflow().getId())
                .orElse(null);
        String learningContext = buildLearningContext(workflowId);

        // Build system prompt
        String systemPrompt = buildSystemPrompt(cfg.goal(), cfg.successCriteria(),
                learningContext, context.getInstructionPrefix());

        // Initialize state
        List<Turn> history = new ArrayList<>();
        history.add(new Turn("user",
                "Begin working on the goal. Analyze what needs to be done and propose your first action."));

        AgenticTokenTracker tokenTracker = new AgenticTokenTracker();
        AgenticSafetyGuard safetyGuard = new AgenticSafetyGuard(
                cfg.totalTimeoutSeconds(), cfg.iterationTimeoutSeconds(), cfg.maxRepeatedActions());

        broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                "agentic_started", Map.of("goal", cfg.goal(), "maxIterations", cfg.maxIterations()));

        StringBuilder fullOutput = new StringBuilder();
        int iteration = 0;
        boolean goalAchieved = false;
        boolean approvalRequired = cfg.requireApproval();
        String stopReason = null;

        while (iteration < cfg.maxIterations() && !context.shouldStop()) {
            // Total timeout check
            String timeoutReason = safetyGuard.checkTotalTimeout();
            if (timeoutReason != null) {
                stopReason = timeoutReason;
                long elapsed = safetyGuard.elapsedSeconds();
                fullOutput.append("[TOTAL TIMEOUT after ").append(elapsed).append("s]\n");
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_total_timeout", Map.of("elapsedSeconds", elapsed));
                break;
            }

            // Handle pause/cancel
            if (!safetyGuard.waitIfPaused(context)) break;
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
                        conversationPrompt, systemPrompt, null, null, cfg.claudeTimeout());
                reasoning = reasonResult.text();
                tokenTracker.captureReasonMeta(iterData, reasonResult, conversationPrompt, reasoning, iteration);
            } catch (Exception e) {
                log.error("Host Claude reasoning failed at iteration {}: {}", iteration, e.getMessage());
                iterData.put("phase", "reason");
                iterData.put("error", e.getMessage());
                tokenTracker.trackIteration(iterData);
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_error", Map.of("iteration", iteration,
                                "phase", "reason", "error", e.getMessage()));
                fullOutput.append("[iteration ").append(iteration)
                        .append(" - REASON ERROR]: ").append(e.getMessage()).append("\n");
                break;
            }

            history.add(new Turn("assistant", reasoning));
            iterData.put("reasoning", truncate(reasoning, TRUNCATE_LONG));

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_reasoned", Map.of("iteration", iteration,
                            "reasoning", truncate(reasoning, TRUNCATE_LONG)));

            // Parse structured response
            AgenticPlan plan = AgenticPlan.parse(reasoning);

            if (plan.isGoalComplete()) {
                goalAchieved = "ACHIEVED".equalsIgnoreCase(plan.getGoalStatus());
                fullOutput.append("[iteration ").append(iteration).append(" - ")
                        .append(plan.getGoalStatus()).append("]: ")
                        .append(plan.getSummary()).append("\n");
                iterData.put("result", plan.getGoalStatus().toLowerCase());
                iterData.put("summary", plan.getSummary());
                tokenTracker.trackIteration(iterData);
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        goalAchieved ? "agentic_goal_achieved" : "agentic_goal_impossible",
                        Map.of("iteration", iteration, "summary", plan.getSummary()));
                break;
            }

            // === Optional APPROVAL gate ===
            if (approvalRequired && !plan.getAction().isEmpty()) {
                String approvalResult = handleApproval(context, stepIndex, iteration, plan, iterData, fullOutput);
                if ("break".equals(approvalResult)) break;
                if ("continue".equals(approvalResult)) {
                    tokenTracker.trackIteration(iterData);
                    continue;
                }
                if ("disable_approval".equals(approvalResult)) {
                    approvalRequired = false;
                }
            }

            // === ACT phase ===
            if (plan.getAction().isEmpty()) {
                history.add(new Turn("user",
                        "Your response did not contain a clear action. Please specify an ACTION to execute."));
                iterData.put("result", "no_action");
                tokenTracker.trackIteration(iterData);
                continue;
            }

            // Compress oversized actions before sending to ACT phase
            String action = plan.getAction();
            if (action.length() > AGENTIC_ACTION_COMPRESS_THRESHOLD) {
                log.warn("Agentic iteration {}: ACTION too long ({} chars), compressing", iteration, action.length());
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_action_compressed",
                        Map.of("iteration", iteration, "originalLength", action.length()));
                try {
                    action = compressAction(action);
                    log.info("Agentic iteration {}: ACTION compressed {} → {} chars",
                            iteration, plan.getAction().length(), action.length());
                } catch (Exception e) {
                    log.error("Agentic iteration {}: compression failed, truncating: {}", iteration, e.getMessage());
                    action = action.substring(0, AGENTIC_ACTION_COMPRESS_THRESHOLD)
                            + "\n\n[TRUNCATED: original was " + plan.getAction().length() + " chars]";
                }
            }

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_acting", Map.of("iteration", iteration,
                            "action", truncate(action, TRUNCATE_MEDIUM)));

            String actionOutput;
            int exitCode;
            try {
                CliSession.EnrichedCommandResult cmdResult = commandExecutor.executeCommandOnHost(
                        context.getProjectPath(), action, context.getModel(),
                        context.getInstructionPrefix(), cfg.claudeTimeout());
                actionOutput = cmdResult.output() != null ? cmdResult.output() : "";
                exitCode = cmdResult.exitCode();
                tokenTracker.captureActMeta(iterData, cmdResult, action, actionOutput, iteration);
            } catch (Exception e) {
                log.error("Host Claude ACT phase failed at iteration {}: {}", iteration, e.getMessage(), e);
                actionOutput = "Execution error: " + e.getMessage();
                exitCode = -1;
            }

            // Track tokens and check for blind iterations
            tokenTracker.trackIteration(iterData);

            String blindReason = tokenTracker.checkBlindIterations();
            if (blindReason != null) {
                stopReason = blindReason;
                int blind = tokenTracker.getConsecutiveBlindIterations();
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_token_tracking_failed",
                        Map.of("iteration", iteration, "consecutiveBlind", blind));
                fullOutput.append("[TOKEN TRACKING FAILED: ").append(blind)
                        .append(" consecutive iterations without real usage data — using fallback estimates, budget enforcement is approximate]\n");
                log.error("Agentic block aborting after {} consecutive iterations without real token usage data", blind);
                break;
            }

            iterData.put("action", action);
            iterData.put("actionOutput", truncate(actionOutput, TRUNCATE_LONG));
            iterData.put("exitCode", exitCode);

            broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                    "agentic_acted", Map.of("iteration", iteration,
                            "action", truncate(action, TRUNCATE_MEDIUM),
                            "output", truncate(actionOutput, TRUNCATE_LONG),
                            "exitCode", exitCode));

            // === OBSERVE phase ===
            String observation = "Action result (exit code " + exitCode + "):\n" + actionOutput;
            history.add(new Turn("user",
                    "Observation from executing your action:\n" + truncate(observation, TRUNCATE_OBSERVATION)
                            + "\n\nAnalyze this result. Has the goal been achieved? "
                            + "If not, what should we do next?"));

            context.setPreviousOutput(actionOutput);

            fullOutput.append("[iteration ").append(iteration).append("]\n")
                    .append("  Reasoning: ").append(truncate(plan.getRationale(), TRUNCATE_SHORT)).append("\n")
                    .append("  Action: ").append(truncate(plan.getAction(), TRUNCATE_SHORT)).append("\n")
                    .append("  Exit code: ").append(exitCode).append("\n")
                    .append("  Result: ").append(truncate(actionOutput, TRUNCATE_MEDIUM)).append("\n\n");

            long iterMs = Instant.now().toEpochMilli() - iterStart.toEpochMilli();
            iterData.put("durationMs", iterMs);

            // Iteration timeout check
            String iterTimeoutReason = safetyGuard.checkIterationTimeout(iterStart);
            if (iterTimeoutReason != null) {
                stopReason = iterTimeoutReason;
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_iteration_timeout", Map.of("iteration", iteration));
                fullOutput.append("[iteration ").append(iteration).append(" - TIMEOUT]\n");
                break;
            }

            // Token budget check
            String budgetReason = tokenTracker.checkBudget(cfg.maxTotalTokens());
            if (budgetReason != null) {
                stopReason = budgetReason;
                int totalTokens = tokenTracker.getRunningTotalTokens();
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_token_budget_exceeded",
                        Map.of("iteration", iteration, "totalTokens", totalTokens,
                                "budget", cfg.maxTotalTokens()));
                fullOutput.append("[TOKEN BUDGET EXCEEDED: ").append(totalTokens)
                        .append("/").append(cfg.maxTotalTokens()).append(" tokens]\n");
                break;
            }

            // Repeated action check
            String repeatReason = safetyGuard.trackAndCheckRepeatedAction(action);
            if (repeatReason != null) {
                stopReason = repeatReason;
                broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                        "agentic_repeated_actions",
                        Map.of("iteration", iteration, "repeatedCount", cfg.maxRepeatedActions()));
                fullOutput.append("[ABORTED: Same action repeated ")
                        .append(cfg.maxRepeatedActions()).append(" times]\n");
                break;
            }

            compressHistory(history);
        }

        // Finalize result
        Instant end = Instant.now();
        StepResult result = StepResult.builder()
                .stepIndex(stepIndex)
                .input("[Agentic] " + cfg.goal())
                .output(fullOutput.toString().trim())
                .startedAt(start)
                .completedAt(end)
                .durationMs(end.toEpochMilli() - start.toEpochMilli())
                .status(goalAchieved ? StepStatus.COMPLETED : StepStatus.FAILED)
                .build();

        result.setMetadata(tokenTracker.buildFinalMetadata(
                cfg.goal(), cfg.successCriteria(), iteration, goalAchieved, stopReason));

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
     * Handles the optional approval gate.
     * @return "break" to break the loop, "continue" to skip to next iteration,
     *         "disable_approval" if "Yes always" was chosen, or null to proceed normally
     */
    private String handleApproval(ExecutionContext context, int stepIndex, int iteration,
                                   AgenticPlan plan, Map<String, Object> iterData,
                                   StringBuilder fullOutput) {
        broadcastAgenticEvent(context.getWorkflowRunId(), stepIndex,
                "agentic_approval_required",
                Map.of("iteration", iteration,
                        "action", plan.getAction(),
                        "rationale", truncate(plan.getRationale(), TRUNCATE_MEDIUM)));

        CompletableFuture<String> future = context.requestApproval(
                ApprovalRequest.builder()
                        .blockId("agentic-" + stepIndex + "-iter-" + iteration)
                        .question("Approve action: " + truncate(plan.getAction(), TRUNCATE_SHORT))
                        .options(List.of("Yes", "Yes always", "No"))
                        .stepIndex(stepIndex)
                        .build());

        String answer;
        try {
            answer = future.get(30, TimeUnit.MINUTES);
        } catch (java.util.concurrent.TimeoutException e) {
            iterData.put("result", "approval_timeout");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL TIMEOUT]\n");
            return "break";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            iterData.put("result", "approval_interrupted");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - INTERRUPTED]\n");
            return "break";
        } catch (Exception e) {
            log.warn("Approval gate failed at iteration {}: {}", iteration, e.getMessage());
            iterData.put("result", "approval_error");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - APPROVAL ERROR: ").append(e.getMessage()).append("]\n");
            return "break";
        }

        if ("No".equalsIgnoreCase(answer)) {
            context.getWorkflowRunId(); // keep reference valid
            var history = new ArrayList<Turn>();
            history.add(new Turn("user",
                    "The user rejected this action. Propose an alternative approach."));
            iterData.put("approval", "rejected");
            fullOutput.append("[iteration ").append(iteration)
                    .append(" - REJECTED]: ").append(plan.getAction()).append("\n");
            return "continue";
        }
        if ("Yes always".equalsIgnoreCase(answer)) {
            iterData.put("approval", "approved");
            return "disable_approval";
        }
        iterData.put("approval", "approved");
        return null;
    }

    /**
     * Compress conversation history to manage context window size.
     * Keeps the first turn (initial prompt) and the most recent 4 turns in full.
     * Middle turns are collapsed into a single summary turn.
     */
    private void compressHistory(List<Turn> history) {
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

    private String compressAction(String oversizedAction) throws IOException {
        String prompt = ACTION_COMPRESS_PROMPT + oversizedAction;
        ReasoningResult result = hostClaude.reasonStructured(
                prompt, null, null, 1, AGENTIC_ACTION_COMPRESS_TIMEOUT);
        String compressed = result.text();
        if (compressed == null || compressed.isBlank()) {
            throw new IOException("Action compression returned empty result");
        }
        return compressed;
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

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen) + "..." : s;
    }
}
