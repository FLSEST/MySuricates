package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.ai.HostClaudeService.Turn;
import com.automation.repository.WorkflowRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.automation.config.ExecutionDefaults.*;
import static com.automation.executor.blocks.AgenticPromptBuilder.truncate;

/**
 * The agentic block: a Reason-Act-Observe loop with safety guards (timeouts, token budget,
 * repeated actions), an optional approval gate and live WebSocket events. Prompt/context
 * construction lives in {@link AgenticPromptBuilder}, event publishing in
 * {@link AgenticEventBroadcaster}; config, plan parsing, safety and token tracking in the
 * sibling {@code Agentic*} helpers.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgenticBlockExecutor implements BlockExecutor {

    private final AgenticPromptBuilder promptBuilder;
    private final AgenticEventBroadcaster events;
    private final WorkflowRunRepository runRepository;
    private final AiProviderRegistry aiProviderRegistry;

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
        String learningContext = promptBuilder.buildLearningContext(workflowId);

        // Build system prompt
        String systemPrompt = promptBuilder.buildSystemPrompt(cfg.goal(), cfg.successCriteria(),
                learningContext, context.getInstructionPrefix());

        // Resolve AI provider
        AiProvider provider = aiProviderRegistry.resolve(context.getProvider());
        String effort = context.effortFor(block);
        log.info("Agentic block using AI provider: {}", provider.getId());

        // Initialize state
        List<Turn> history = new ArrayList<>();
        history.add(new Turn("user",
                "Begin working on the goal. Analyze what needs to be done and propose your first action."));

        AgenticTokenTracker tokenTracker = new AgenticTokenTracker();
        if (!provider.getCapabilities().tokenTracking()) {
            tokenTracker.setExpectEstimatedTokens(true);
        }
        AgenticSafetyGuard safetyGuard = new AgenticSafetyGuard(
                cfg.totalTimeoutSeconds(), cfg.iterationTimeoutSeconds(), cfg.maxRepeatedActions());

        events.broadcast(context.getWorkflowRunId(), stepIndex,
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
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
            events.broadcast(context.getWorkflowRunId(), stepIndex,
                    "agentic_reasoning", Map.of("iteration", iteration));

            String reasoning;
            try {
                String conversationPrompt = provider.buildConversationPrompt(systemPrompt, history);
                AiProvider.ReasoningResult reasonResult = provider.reason(
                        new AiProvider.ReasoningRequest(conversationPrompt, systemPrompt, context.getModel(),
                                null, null, cfg.claudeTimeout(), effort));
                reasoning = reasonResult.text();
                tokenTracker.captureReasonMeta(iterData, reasonResult.metadata(), conversationPrompt, reasoning, iteration);
            } catch (Exception e) {
                log.error("Host Claude reasoning failed at iteration {}: {}", iteration, e.getMessage());
                iterData.put("phase", "reason");
                iterData.put("error", e.getMessage());
                tokenTracker.trackIteration(iterData);
                events.broadcast(context.getWorkflowRunId(), stepIndex,
                        "agentic_error", Map.of("iteration", iteration,
                                "phase", "reason", "error", e.getMessage()));
                fullOutput.append("[iteration ").append(iteration)
                        .append(" - REASON ERROR]: ").append(e.getMessage()).append("\n");
                break;
            }

            history.add(new Turn("assistant", reasoning));
            iterData.put("reasoning", truncate(reasoning, TRUNCATE_LONG));

            events.broadcast(context.getWorkflowRunId(), stepIndex,
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
                        "agentic_action_compressed",
                        Map.of("iteration", iteration, "originalLength", action.length()));
                try {
                    action = promptBuilder.compressAction(action, provider, context.getModel());
                    log.info("Agentic iteration {}: ACTION compressed {} → {} chars",
                            iteration, plan.getAction().length(), action.length());
                } catch (Exception e) {
                    log.error("Agentic iteration {}: compression failed, truncating: {}", iteration, e.getMessage());
                    action = action.substring(0, AGENTIC_ACTION_COMPRESS_THRESHOLD)
                            + "\n\n[TRUNCATED: original was " + plan.getAction().length() + " chars]";
                }
            }

            events.broadcast(context.getWorkflowRunId(), stepIndex,
                    "agentic_acting", Map.of("iteration", iteration,
                            "action", truncate(action, TRUNCATE_MEDIUM)));

            String actionOutput;
            int exitCode;
            try {
                AiProvider.ActionResult actResult = provider.executeAction(
                        new AiProvider.ActionRequest(action, context.getModel(),
                                context.getInstructionPrefix(), context.getProjectPath(),
                                cfg.claudeTimeout(), false, false, null, null, effort));
                actionOutput = actResult.text() != null ? actResult.text() : "";
                exitCode = actResult.exitCode();
                tokenTracker.captureActMeta(iterData, actResult.metadata(), action, actionOutput, iteration);
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
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

            events.broadcast(context.getWorkflowRunId(), stepIndex,
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
                        "agentic_iteration_timeout", Map.of("iteration", iteration));
                fullOutput.append("[iteration ").append(iteration).append(" - TIMEOUT]\n");
                break;
            }

            // Token budget check
            String budgetReason = tokenTracker.checkBudget(cfg.maxTotalTokens());
            if (budgetReason != null) {
                stopReason = budgetReason;
                int totalTokens = tokenTracker.getRunningTotalTokens();
                events.broadcast(context.getWorkflowRunId(), stepIndex,
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
                events.broadcast(context.getWorkflowRunId(), stepIndex,
                        "agentic_repeated_actions",
                        Map.of("iteration", iteration, "repeatedCount", cfg.maxRepeatedActions()));
                fullOutput.append("[ABORTED: Same action repeated ")
                        .append(cfg.maxRepeatedActions()).append(" times]\n");
                break;
            }

            promptBuilder.compressHistory(history);
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
                cfg.goal(), cfg.successCriteria(), iteration, goalAchieved, stopReason,
                context.getModel(), provider.getId()));

        context.setVariable("agentic_goal_achieved", goalAchieved);
        context.setVariable("agentic_iterations", iteration);

        events.broadcast(context.getWorkflowRunId(), stepIndex,
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
        events.broadcast(context.getWorkflowRunId(), stepIndex,
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
}
