package com.automation.service.wish;

import com.automation.entity.Wish;
import com.automation.entity.WishStatus;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase C of the wish meta-loop: asks the default AI provider whether the generated workflows
 * satisfy the wish, stores the evaluation in the wish metadata and moves the wish to
 * AWAITING_APPROVAL / COMPLETED, REFINING or FAILED.
 *
 * <p>Extracted from {@code WishService} (Audit-TODO fc137a98), mirroring the existing
 * {@link WishDecomposeService} / {@link WishExecutionService} split. Does <b>not</b> drive the
 * loop — that is {@link WishFulfillmentLoop}'s job.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WishEvaluationService {

    private final WishPromptBuilder promptBuilder;
    private final AiProviderRegistry aiProviderRegistry;
    private final WishRepository wishRepository;
    private final SimpMessagingTemplate messagingTemplate;

    @SuppressWarnings("unchecked")
    public void evaluate(Wish wish) throws Exception {
        log.info("Wish {}: Evaluating results", wish.getId());

        String prompt = promptBuilder.buildEvaluatePrompt(wish);
        AiProvider defaultProvider = aiProviderRegistry.getDefault();
        AiProvider.ReasoningResult result = defaultProvider.reason(new AiProvider.ReasoningRequest(prompt));

        Map<String, Object> evaluation;
        try {
            evaluation = WishJsonUtil.parseJsonSafely(result.text(), "evaluate");
        } catch (IllegalStateException e) {
            log.warn("Wish {}: Evaluate JSON parse failed, retrying with shorter prompt", wish.getId());
            String retryPrompt = prompt + "\n\nIMPORTANT: Keep response under 500 tokens. Respond ONLY with valid JSON, no markdown.";
            AiProvider.ReasoningResult retryResult = defaultProvider.reason(new AiProvider.ReasoningRequest(retryPrompt));
            evaluation = WishJsonUtil.parseJsonSafely(retryResult.text(), "evaluate-retry");
        }

        Object satisfiedRaw = evaluation.get("satisfied");
        boolean satisfied = Boolean.TRUE.equals(satisfiedRaw);
        boolean shouldRetry = Boolean.TRUE.equals(evaluation.get("shouldRetry"));
        String summary = evaluation.get("summary") instanceof String s ? s : "";
        List<?> issues = evaluation.get("issues") instanceof List<?> l ? l : List.of();

        // Empty evaluation detection
        if (satisfiedRaw == null && summary.isBlank() && issues.isEmpty()) {
            log.warn("Wish {}: Empty evaluation response — evaluation call likely failed. Treating as retry.", wish.getId());
            shouldRetry = true;
            summary = "Evaluation returned empty response (CLI failure). Automatic retry.";
            evaluation.put("_emptyEvaluation", true);
            evaluation.put("summary", summary);
            evaluation.put("shouldRetry", true);
        }
        // Ambiguous evaluation: satisfied=null but good summary with no issues → treat as satisfied
        else if (satisfiedRaw == null && issues.isEmpty() && !summary.isBlank()) {
            log.warn("Wish {}: satisfied=null with no issues — treating as satisfied", wish.getId());
            satisfied = true;
            evaluation.put("satisfied", true);
            evaluation.put("_inferredSatisfied", true);
        }

        // Store evaluation in metadata
        Map<String, Object> meta = new HashMap<>(wish.getMetadata());
        List<Object> evaluations;
        if (meta.get("evaluations") instanceof List<?> existing) {
            evaluations = new ArrayList<>();
            evaluations.addAll(existing);
        } else {
            evaluations = new ArrayList<>();
        }
        evaluations.add(evaluation);
        meta.put("evaluations", evaluations);
        wish.setMetadata(meta);

        // Add evaluation to current iteration history entry
        WishStatusHelper.updateCurrentIterationEntry(wish, "evaluation", new HashMap<>(evaluation));

        if (satisfied) {
            meta.put("pendingEvaluation", evaluation);
            wish.setMetadata(meta);
            wish.setResultSummary(summary);
            if (wish.isAutoRefine()) {
                WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.COMPLETED);
                log.info("Wish {}: Auto-accepted (autoRefine) — {}", wish.getId(), WishStringUtil.truncate(summary, 100));
            } else {
                WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.AWAITING_APPROVAL);
                log.info("Wish {}: Awaiting user approval — {}", wish.getId(), WishStringUtil.truncate(summary, 100));
            }
            return;
        } else if (shouldRetry && wish.getIterationCount() < wish.getMaxIterations()) {
            wish.setStatus(WishStatus.REFINING);
            wish.setResultSummary(summary);
            log.info("Wish {}: Refining (iteration {}/{})", wish.getId(), wish.getIterationCount(), wish.getMaxIterations());
        } else {
            wish.setStatus(WishStatus.FAILED);
            wish.setResultSummary(summary);
            wish.setError("Not satisfied after " + wish.getIterationCount() + " iterations. Issues: " + issues);
            log.info("Wish {}: Failed — {}", wish.getId(), WishStringUtil.truncate(summary, 100));
        }

        wishRepository.save(wish);
        WishStatusHelper.broadcastStatus(messagingTemplate, wish.getId(), wish);
    }
}
