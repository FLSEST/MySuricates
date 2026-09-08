package com.automation.service.wish;

import com.automation.dto.request.WishRespondRequest;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.workflow.WorkflowTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * Wish CRUD, the user-response state machine ({@code respond}) and crystallisation into a
 * template. The asynchronous meta-loop and cancellation live in {@link WishFulfillmentLoop},
 * the evaluation phase in {@link WishEvaluationService}.
 */
@Service
@Slf4j
public class WishService {

    private final WishRepository wishRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowTemplateService workflowTemplateService;
    private final SimpMessagingTemplate messagingTemplate;
    private final WishFulfillmentLoop fulfillmentLoop;

    public WishService(
            WishRepository wishRepository,
            ProjectRepository projectRepository,
            @Lazy WorkflowTemplateService workflowTemplateService,
            SimpMessagingTemplate messagingTemplate,
            WishFulfillmentLoop fulfillmentLoop
    ) {
        this.wishRepository = wishRepository;
        this.projectRepository = projectRepository;
        this.workflowTemplateService = workflowTemplateService;
        this.messagingTemplate = messagingTemplate;
        this.fulfillmentLoop = fulfillmentLoop;
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
                    fulfillmentLoop.fulfillAsync(wishId);
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
                    fulfillmentLoop.fulfillAsync(wishId);
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
                fulfillmentLoop.fulfillAsync(wishId);
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
                fulfillmentLoop.fulfillAsync(wishId);
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
                fulfillmentLoop.fulfillAsync(wishId);
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

    // ── Helpers ──────────────────────────────────────────────────

    void broadcastStatus(String wishId, Wish wish) {
        WishStatusHelper.broadcastStatus(messagingTemplate, wishId, wish);
    }
}
