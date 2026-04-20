package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.dto.request.WishRespondRequest;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.learning.LearningService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class WishService {

    private final WishConfig wishConfig;
    private final WishRepository wishRepository;
    private final AiProviderRegistry aiProviderRegistry;
    private final WorkflowService workflowService;
    private final ProjectRepository projectRepository;
    private final WorkflowTemplateService workflowTemplateService;
    private final LearningService learningService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;
    private final WishPromptBuilder promptBuilder;
    private final WishDecomposeService decomposeService;
    private final WishExecutionService executionService;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    public WishService(
            WishConfig wishConfig,
            WishRepository wishRepository,
            AiProviderRegistry aiProviderRegistry,
            WorkflowService workflowService,
            ProjectRepository projectRepository,
            @Lazy WorkflowTemplateService workflowTemplateService,
            LearningService learningService,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor,
            WishPromptBuilder promptBuilder,
            WishDecomposeService decomposeService,
            WishExecutionService executionService
    ) {
        this.wishConfig = wishConfig;
        this.wishRepository = wishRepository;
        this.aiProviderRegistry = aiProviderRegistry;
        this.workflowService = workflowService;
        this.projectRepository = projectRepository;
        this.workflowTemplateService = workflowTemplateService;
        this.learningService = learningService;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
        this.promptBuilder = promptBuilder;
        this.decomposeService = decomposeService;
        this.executionService = executionService;
    }

    // ── CRUD ─────────────────────────────────────────────────────

    public List<Wish> findAll() {
        return wishRepository.findAllByOrderByCreatedAtDesc();
    }

    public Wish findById(String id) {
        return wishRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Wish not found: " + id));
    }

    public Wish create(String text, String projectId, Integer maxIterations, List<String> referenceWorkflowIds) {
        return create(text, projectId, maxIterations, referenceWorkflowIds, null);
    }

    public Wish create(String text, String projectId, Integer maxIterations, List<String> referenceWorkflowIds, Boolean autoRefine) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("projectId is required for wish creation");
        }
        projectRepository.findById(projectId)
                .orElseThrow(() -> new NoSuchElementException("Project not found: " + projectId));

        Wish wish = Wish.builder()
                .originalText(text)
                .status(WishStatus.RECEIVED)
                .projectId(projectId)
                .maxIterations(maxIterations != null ? maxIterations : 5)
                .referenceWorkflowIds(referenceWorkflowIds != null ? referenceWorkflowIds : List.of())
                .autoRefine(Boolean.TRUE.equals(autoRefine))
                .build();

        return wishRepository.save(wish);
    }

    public void delete(String id) {
        Wish wish = findById(id);
        if (wish.getStatus() != WishStatus.COMPLETED
                && wish.getStatus() != WishStatus.FAILED
                && wish.getStatus() != WishStatus.CANCELLED) {
            throw new IllegalStateException("Can only delete wishes in terminal state (COMPLETED/FAILED/CANCELLED)");
        }
        wishRepository.delete(wish);
    }

    // ── Async Orchestration ──────────────────────────────────────

    public void fulfillAsync(String wishId) {
        activeCancellations.put(wishId, new AtomicBoolean(false));
        virtualThreadExecutor.submit(() -> fulfillWish(wishId));
    }

    public void cancel(String wishId) {
        AtomicBoolean flag = activeCancellations.get(wishId);
        if (flag != null) {
            flag.set(true);
        }
        Wish wish = wishRepository.findById(wishId).orElse(null);
        if (wish != null && !WishStatusHelper.isTerminal(wish.getStatus())) {
            wish.setStatus(WishStatus.CANCELLED);
            wishRepository.save(wish);
            broadcastStatus(wishId, wish);
        }
    }

    // ── Respond & Crystallize ────────────────────────────────────

    @SuppressWarnings("unchecked")
    public Wish respond(String wishId, WishRespondRequest request) {
        Wish wish = findById(wishId);

        return switch (request.action()) {
            case ACCEPT -> {
                if (wish.getStatus() == WishStatus.AWAITING_DECOMPOSE_APPROVAL) {
                    wish.setStatus(WishStatus.EXECUTING);
                    wishRepository.save(wish);
                    broadcastStatus(wishId, wish);
                    log.info("Wish {}: User approved decomposition, starting execution", wishId);
                    fulfillAsync(wishId);
                    yield wish;
                }
                if (wish.getStatus() != WishStatus.AWAITING_APPROVAL) {
                    throw new IllegalStateException("Can only accept wishes in AWAITING_APPROVAL or AWAITING_DECOMPOSE_APPROVAL state");
                }
                wish.setStatus(WishStatus.COMPLETED);
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
                log.info("Wish {}: User accepted", wishId);
                yield wish;
            }
            case REFINE -> {
                if (wish.getStatus() == WishStatus.AWAITING_DECOMPOSE_APPROVAL) {
                    Map<String, Object> meta = new HashMap<>(wish.getMetadata());
                    meta.put("decomposeFeedback", request.feedback());
                    wish.setMetadata(meta);
                    wish.setStatus(WishStatus.REFINING);
                    wishRepository.save(wish);
                    broadcastStatus(wishId, wish);
                    log.info("Wish {}: User requested re-decomposition", wishId);
                    fulfillAsync(wishId);
                    yield wish;
                }
                if (wish.getStatus() != WishStatus.AWAITING_APPROVAL) {
                    throw new IllegalStateException("Can only refine wishes in AWAITING_APPROVAL or AWAITING_DECOMPOSE_APPROVAL state");
                }
                Map<String, Object> meta = new HashMap<>(wish.getMetadata());
                meta.put("userFeedback", request.feedback());
                wish.setMetadata(meta);
                wish.setStatus(WishStatus.REFINING);
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
                log.info("Wish {}: User requested refinement", wishId);
                fulfillAsync(wishId);
                yield wish;
            }
            case CANCEL -> {
                if (!WishStatusHelper.isPaused(wish.getStatus())) {
                    throw new IllegalStateException("Can only cancel wishes in AWAITING_* state");
                }
                wish.setStatus(WishStatus.CANCELLED);
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
                log.info("Wish {}: User cancelled", wishId);
                yield wish;
            }
            case CLARIFY -> {
                if (wish.getStatus() != WishStatus.AWAITING_CLARIFICATION) {
                    throw new IllegalStateException("Can only clarify wishes in AWAITING_CLARIFICATION state");
                }
                Map<String, Object> meta = new HashMap<>(wish.getMetadata());
                List<Map<String, String>> clarifications;
                if (meta.get("clarifications") instanceof List<?> existing) {
                    clarifications = new ArrayList<>((List<Map<String, String>>) existing);
                } else {
                    clarifications = new ArrayList<>();
                }
                clarifications.add(Map.of(
                        "question", (String) meta.getOrDefault("clarificationQuestion", ""),
                        "answer", request.feedback() != null ? request.feedback() : ""
                ));
                meta.put("clarifications", clarifications);
                meta.remove("clarificationQuestion");
                wish.setMetadata(meta);
                wish.setStatus(WishStatus.RECEIVED);
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
                log.info("Wish {}: User provided clarification", wishId);
                fulfillAsync(wishId);
                yield wish;
            }
            case RETRY -> {
                if (wish.getStatus() != WishStatus.FAILED && wish.getStatus() != WishStatus.CANCELLED) {
                    throw new IllegalStateException("Can only retry wishes in FAILED or CANCELLED state");
                }
                wish.setStatus(WishStatus.RECEIVED);
                wish.setError(null);
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
                log.info("Wish {}: User requested retry (iteration {})", wishId, wish.getIterationCount());
                fulfillAsync(wishId);
                yield wish;
            }
        };
    }

    public String crystallize(String wishId) {
        Wish wish = findById(wishId);
        if (wish.getStatus() != WishStatus.COMPLETED) {
            throw new IllegalStateException("Can only crystallize completed wishes");
        }
        if (wish.getGeneratedWorkflowIds() == null || wish.getGeneratedWorkflowIds().isEmpty()) {
            throw new IllegalStateException("No generated workflows to crystallize");
        }

        String workflowId = wish.getGeneratedWorkflowIds().get(0);
        WorkflowTemplate template = workflowTemplateService.createFromWorkflow(workflowId);

        Map<String, Object> meta = new HashMap<>(wish.getMetadata());
        meta.put("crystallizedTemplateId", template.getId());
        wish.setMetadata(meta);
        wishRepository.save(wish);

        log.info("Wish {}: Crystallized as template {}", wishId, template.getId());
        return template.getId();
    }

    // ── Meta-Loop ────────────────────────────────────────────────

    private void fulfillWish(String wishId) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(wishConfig.getTotalTimeoutMinutes()));

        try {
            while (true) {
                if (isCancelled(wishId)) {
                    Wish w = findById(wishId);
                    w.setStatus(WishStatus.CANCELLED);
                    wishRepository.save(w);
                    broadcastStatus(wishId, w);
                    return;
                }
                if (Instant.now().isAfter(deadline)) {
                    Wish w = findById(wishId);
                    w.setStatus(WishStatus.FAILED);
                    w.setError("Total timeout exceeded (" + wishConfig.getTotalTimeoutMinutes() + " minutes)");
                    wishRepository.save(w);
                    broadcastStatus(wishId, w);
                    return;
                }

                Wish wish = findById(wishId);

                if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;

                // Phase 1: Decompose
                if (wish.getStatus() == WishStatus.RECEIVED || wish.getStatus() == WishStatus.REFINING) {
                    WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.DECOMPOSING);
                    decomposeService.decompose(wish);
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                }

                // Phase 2: Execute
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EXECUTING) {
                    executionService.execute(wish, () -> isCancelled(wishId));
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                }

                // Phase 3: Evaluate
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EVALUATING) {
                    evaluate(wish);
                    wish = findById(wishId);
                    if (WishStatusHelper.isTerminal(wish.getStatus()) || WishStatusHelper.isPaused(wish.getStatus())) return;
                    // If REFINING, loop continues
                }
            }
        } catch (Exception e) {
            log.error("Wish {} failed unexpectedly", wishId, e);
            try {
                Wish wish = findById(wishId);
                wish.setStatus(WishStatus.FAILED);
                wish.setError(e.getMessage());
                wishRepository.save(wish);
                broadcastStatus(wishId, wish);
            } catch (Exception ex) {
                log.error("Failed to update wish status after error", ex);
            }
        } finally {
            activeCancellations.remove(wishId);
        }
    }

    // ── Phase C: Evaluate ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void evaluate(Wish wish) throws Exception {
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
        broadcastStatus(wish.getId(), wish);
    }

    // ── Helpers ──────────────────────────────────────────────────

    private boolean isCancelled(String wishId) {
        AtomicBoolean flag = activeCancellations.get(wishId);
        return flag != null && flag.get();
    }

    void broadcastStatus(String wishId, Wish wish) {
        WishStatusHelper.broadcastStatus(messagingTemplate, wishId, wish);
    }
}
