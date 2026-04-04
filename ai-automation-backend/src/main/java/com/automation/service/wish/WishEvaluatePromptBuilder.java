package com.automation.service.wish;

import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.service.learning.LearningService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Builds the evaluate prompt for wishes.
 * Extracted from WishPromptBuilder to separate evaluation-specific logic.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WishEvaluatePromptBuilder {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final StepResultRepository stepResultRepository;
    private final LearningService learningService;

    @SuppressWarnings("unchecked")
    public String buildPrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Wish\n\n");
        sb.append(wish.getOriginalText()).append("\n\n");

        // Include decomposition summary (truncate if very large)
        sb.append("## Generated Workflow Structure\n\n");
        if (wish.getDecomposition() != null) {
            String decompositionJson = MAPPER.valueToTree(wish.getDecomposition()).toPrettyString();
            if (decompositionJson.length() > 3000) {
                sb.append("Analysis: ").append(wish.getDecomposition().getOrDefault("analysis", "")).append("\n");
                sb.append("Strategy: ").append(wish.getDecomposition().getOrDefault("strategy", "")).append("\n");
                Object wfs = wish.getDecomposition().get("workflows");
                if (wfs instanceof List<?> wfList) {
                    for (Object wfObj : wfList) {
                        if (wfObj instanceof Map<?, ?> wfMap) {
                            sb.append("- Workflow: ").append(wfMap.get("name")).append("\n");
                        }
                    }
                }
                sb.append("\n");
            } else {
                sb.append(decompositionJson).append("\n\n");
            }
        }

        sb.append("## Execution Results\n\n");

        if (wish.getGeneratedChainId() != null && wish.getCurrentRunId() != null) {
            chainRunRepository.findById(wish.getCurrentRunId()).ifPresent(chainRun -> {
                sb.append("Chain Status: ").append(chainRun.getStatus()).append("\n");
                if (chainRun.getError() != null) {
                    sb.append("Chain Error: ").append(chainRun.getError()).append("\n");
                }
                for (ChainRunEntry entry : chainRun.getEntryRuns()) {
                    sb.append("- Workflow '").append(entry.getWorkflowName())
                            .append("': ").append(entry.getStatus()).append("\n");
                    if (entry.getWorkflowRunId() != null) {
                        appendWorkflowRunOutput(sb, entry.getWorkflowRunId());
                    }
                }
            });
        } else if (wish.getCurrentRunId() != null) {
            appendWorkflowRunOutput(sb, wish.getCurrentRunId());
        }

        // Add execution cost context
        if (wish.getCurrentRunId() != null) {
            try {
                Map<String, Object> runInsights = learningService.getRunInsights(wish.getCurrentRunId());
                double cost = ((Number) runInsights.getOrDefault("totalCostUsd", 0.0)).doubleValue();
                long tokens = ((Number) runInsights.getOrDefault("totalTokens", 0L)).longValue();
                long durationMs = ((Number) runInsights.getOrDefault("durationMs", 0L)).longValue();
                if (cost > 0 || tokens > 0) {
                    sb.append("\n## Execution Cost\n\n");
                    sb.append(String.format("- Cost: $%.4f, Tokens: %d, Duration: %.0fs\n",
                            cost, tokens, durationMs / 1000.0));
                    sb.append("- Consider cost efficiency when suggesting improvements\n\n");
                }
            } catch (Exception e) {
                log.debug("Could not add run insights to evaluate prompt: {}", e.getMessage());
            }
        }

        // Add verification results if available
        if (wish.getMetadata() != null && wish.getMetadata().get("verificationResults") instanceof List<?> vResults && !vResults.isEmpty()) {
            sb.append("## Pre-flight Verification Results\n\n");
            for (Object vr : vResults) {
                if (vr instanceof Map<?, ?> vrMap) {
                    sb.append("- Workflow ").append(vrMap.get("workflowId"))
                            .append(": ").append(vrMap.get("status"));
                    Object err = vrMap.get("error");
                    if (err != null && !String.valueOf(err).isBlank()) {
                        sb.append(" (").append(WishStringUtil.truncate(String.valueOf(err), 100)).append(")");
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        // Cap total prompt size to prevent empty/truncated Claude responses
        String task = """

                ## Task

                Evaluate whether the wish has been fulfilled.

                CRITICAL: Your ENTIRE response must be a single JSON object. No text before or after. No markdown fences. No explanations. Just the raw JSON object starting with { and ending with }.

                {
                  "satisfied": true/false,
                  "summary": "What was achieved",
                  "issues": ["Issue 1", "Issue 2"],
                  "suggestions": ["Improvement 1", "Improvement 2"],
                  "shouldRetry": true/false
                }
                """;

        int maxContextLength = 15000;
        if (sb.length() > maxContextLength) {
            log.info("Wish evaluation prompt truncated from {} to {} chars", sb.length(), maxContextLength);
            sb.setLength(maxContextLength);
            sb.append("\n\n[... context truncated for prompt size ...]\n");
        }
        sb.append(task);

        return sb.toString();
    }

    private void appendWorkflowRunOutput(StringBuilder sb, String runId) {
        workflowRunRepository.findById(runId).ifPresent(run -> {
            sb.append("  **Run Status: ").append(run.getStatus()).append("**\n");
            if (run.getError() != null && !run.getError().isEmpty()) {
                sb.append("  Error: ").append(WishStringUtil.truncate(run.getError().toString(), 200)).append("\n");
            }
            List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId);
            int maxDetailedSteps = 8;
            int startIdx = Math.max(0, steps.size() - maxDetailedSteps);

            if (startIdx > 0) {
                long succeeded = steps.subList(0, startIdx).stream()
                        .filter(s -> "COMPLETED".equals(String.valueOf(s.getStatus())))
                        .count();
                sb.append("  [Steps 0-").append(startIdx - 1).append(": ")
                        .append(succeeded).append("/").append(startIdx).append(" succeeded]\n");
            }

            for (int i = startIdx; i < steps.size(); i++) {
                StepResult step = steps.get(i);
                String blockType = step.getMetadata() != null
                        ? (String) step.getMetadata().getOrDefault("blockType", "unknown")
                        : "unknown";
                sb.append("  Step ").append(step.getStepIndex()).append(" (").append(blockType).append("): ");
                sb.append(step.getStatus()).append("\n");
                if (step.getOutput() != null) {
                    sb.append("    Output: ").append(WishStringUtil.truncate(step.getOutput(), 300)).append("\n");
                }
            }
        });
    }
}
