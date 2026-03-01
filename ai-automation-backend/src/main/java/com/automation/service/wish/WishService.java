package com.automation.service.wish;

import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.WishRespondRequest;
import com.automation.entity.*;
import com.automation.repository.*;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.learning.LearningService;
import com.automation.service.pool.WorkflowPoolService;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static com.automation.config.ExecutionDefaults.POLLING_INTERVAL_MS;

@Service
@Slf4j
public class WishService {

    private static final int TOTAL_TIMEOUT_MINUTES = 120;
    private static final int WORKFLOW_WAIT_MINUTES = 30;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WishRepository wishRepository;
    private final HostClaudeService hostClaude;
    private final WorkflowService workflowService;
    private final ChainService chainService;
    private final ChainExecutor chainExecutor;
    private final WorkflowExecutor workflowExecutor;
    private final WorkflowRepository workflowRepository;
    private final SkillRepository skillRepository;
    private final WorkflowRunRepository workflowRunRepository;
    private final ChainRunRepository chainRunRepository;
    private final StepResultRepository stepResultRepository;
    private final ProjectRepository projectRepository;
    private final WorkflowTemplateRepository templateRepository;
    private final WorkflowMemoryRepository memoryRepository;
    private final AgentInstructionsService agentInstructionsService;
    private final WorkflowTemplateService workflowTemplateService;
    private final WorkflowPoolService workflowPoolService;
    private final WorkflowFactoryService workflowFactoryService;
    private final LearningService learningService;
    private final SimpMessagingTemplate messagingTemplate;
    private final ExecutorService virtualThreadExecutor;

    private final Map<String, AtomicBoolean> activeCancellations = new ConcurrentHashMap<>();

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "that", "this", "from", "will", "have", "been",
            "are", "was", "were", "not", "but", "all", "can", "had", "her", "his",
            "one", "our", "out", "you", "its", "also", "into", "just", "than", "them",
            "then", "what", "when", "who", "how", "about", "which", "would", "there",
            "their", "should", "could", "does", "each", "more", "some", "such", "only",
            "der", "die", "das", "und", "ein", "eine", "ist", "ich", "sie", "den",
            "auf", "mit", "dem", "nicht", "sich", "des", "von", "aus", "hat", "noch",
            "nach", "bei", "wie", "nur", "über", "aber", "vor", "auch", "dann", "soll",
            "für", "oder", "wird", "alle", "mein", "dein", "sein", "ihr", "wir"
    );

    private static final Pattern WORD_SPLIT = Pattern.compile("[\\s,;:.!?()\\[\\]{}\"/]+");

    public WishService(
            WishRepository wishRepository,
            HostClaudeService hostClaude,
            WorkflowService workflowService,
            ChainService chainService,
            ChainExecutor chainExecutor,
            @Lazy WorkflowExecutor workflowExecutor,
            WorkflowRepository workflowRepository,
            SkillRepository skillRepository,
            WorkflowRunRepository workflowRunRepository,
            ChainRunRepository chainRunRepository,
            StepResultRepository stepResultRepository,
            ProjectRepository projectRepository,
            WorkflowTemplateRepository templateRepository,
            WorkflowMemoryRepository memoryRepository,
            AgentInstructionsService agentInstructionsService,
            @Lazy WorkflowTemplateService workflowTemplateService,
            WorkflowPoolService workflowPoolService,
            @Lazy WorkflowFactoryService workflowFactoryService,
            LearningService learningService,
            SimpMessagingTemplate messagingTemplate,
            ExecutorService virtualThreadExecutor
    ) {
        this.wishRepository = wishRepository;
        this.hostClaude = hostClaude;
        this.workflowService = workflowService;
        this.chainService = chainService;
        this.chainExecutor = chainExecutor;
        this.workflowExecutor = workflowExecutor;
        this.workflowRepository = workflowRepository;
        this.skillRepository = skillRepository;
        this.workflowRunRepository = workflowRunRepository;
        this.chainRunRepository = chainRunRepository;
        this.stepResultRepository = stepResultRepository;
        this.projectRepository = projectRepository;
        this.templateRepository = templateRepository;
        this.memoryRepository = memoryRepository;
        this.agentInstructionsService = agentInstructionsService;
        this.workflowTemplateService = workflowTemplateService;
        this.workflowPoolService = workflowPoolService;
        this.workflowFactoryService = workflowFactoryService;
        this.learningService = learningService;
        this.messagingTemplate = messagingTemplate;
        this.virtualThreadExecutor = virtualThreadExecutor;
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
        if (projectId != null) {
            projectRepository.findById(projectId)
                    .orElseThrow(() -> new NoSuchElementException("Project not found: " + projectId));
        }

        Wish wish = Wish.builder()
                .originalText(text)
                .status(WishStatus.RECEIVED)
                .projectId(projectId)
                .maxIterations(maxIterations != null ? maxIterations : 5)
                .referenceWorkflowIds(referenceWorkflowIds != null ? referenceWorkflowIds : List.of())
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
        if (wish != null && !isTerminal(wish.getStatus())) {
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
                if (!isPaused(wish.getStatus())) {
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
        Instant deadline = Instant.now().plus(Duration.ofMinutes(TOTAL_TIMEOUT_MINUTES));

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
                    w.setError("Total timeout exceeded (" + TOTAL_TIMEOUT_MINUTES + " minutes)");
                    wishRepository.save(w);
                    broadcastStatus(wishId, w);
                    return;
                }

                Wish wish = findById(wishId);

                if (isTerminal(wish.getStatus()) || isPaused(wish.getStatus())) return;

                // Phase 1: Decompose
                if (wish.getStatus() == WishStatus.RECEIVED || wish.getStatus() == WishStatus.REFINING) {
                    updateStatus(wish, WishStatus.DECOMPOSING);
                    decompose(wish);
                    wish = findById(wishId);
                    if (isTerminal(wish.getStatus()) || isPaused(wish.getStatus())) return;
                }

                // Phase 2: Execute
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EXECUTING) {
                    execute(wish);
                    wish = findById(wishId);
                    if (isTerminal(wish.getStatus()) || isPaused(wish.getStatus())) return;
                }

                // Phase 3: Evaluate
                wish = findById(wishId);
                if (wish.getStatus() == WishStatus.EVALUATING) {
                    evaluate(wish);
                    wish = findById(wishId);
                    if (isTerminal(wish.getStatus()) || isPaused(wish.getStatus())) return;
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

    // ── Phase A: Decompose ───────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void decompose(Wish wish) throws Exception {
        log.info("Wish {}: Decomposing '{}'", wish.getId(), truncate(wish.getOriginalText(), 80));

        String prompt = buildDecomposePrompt(wish);
        String systemPrompt = buildDecomposeSystemPrompt(wish);

        String workingDir = null;
        if (wish.getProjectId() != null) {
            workingDir = projectRepository.findById(wish.getProjectId())
                    .map(p -> p.getPath())
                    .orElse(null);
        }

        HostClaudeService.ReasoningResult result = hostClaude.reasonStructured(
                prompt, systemPrompt, workingDir, 3);

        String json = extractJson(result.text());
        Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});

        wish.setDecomposition(parsed);

        // Check if clarification is needed
        Boolean needsClarification = (Boolean) parsed.get("needsClarification");
        if (Boolean.TRUE.equals(needsClarification)) {
            String question = (String) parsed.getOrDefault("clarificationQuestion", "Could you provide more details?");
            Map<String, Object> meta = new HashMap<>(wish.getMetadata());
            meta.put("clarificationQuestion", question);
            wish.setMetadata(meta);
            updateStatus(wish, WishStatus.AWAITING_CLARIFICATION);
            return;
        }

        // Create workflows from decomposition
        List<String> workflowIds = new ArrayList<>();
        Object workflowsObj = parsed.get("workflows");
        if (workflowsObj instanceof List<?> workflowList) {
            for (Object wfObj : workflowList) {
                if (!(wfObj instanceof Map<?, ?> wfMap)) continue;
                Map<String, Object> wf = (Map<String, Object>) wfMap;

                String name = (String) wf.getOrDefault("name", "Wish-Workflow");
                String description = (String) wf.getOrDefault("description", "");

                List<BlockDefinition> blocks = parseBlocks(wf.get("blocks"));

                CreateWorkflowRequest req = new CreateWorkflowRequest(
                        wish.getProjectId(),
                        name,
                        description,
                        null,
                        blocks,
                        List.of(),
                        null,
                        null,
                        null,
                        null,
                        null
                );

                Workflow created = workflowService.create(req);
                workflowIds.add(created.getId());
                log.info("Wish {}: Created workflow '{}' ({})", wish.getId(), name, created.getId());
            }
        }

        wish.setGeneratedWorkflowIds(workflowIds);

        // Pre-flight verification on Test instance (best effort)
        verifyGeneratedWorkflows(wish, workflowIds);

        // Create chain if multiple workflows
        Object chainObj = parsed.get("chain");
        if (workflowIds.size() > 1 && chainObj instanceof Map<?, ?> chainMap) {
            Map<String, Object> chainDef = (Map<String, Object>) chainObj;
            String chainName = (String) chainDef.getOrDefault("name", "Wish-Chain: " + truncate(wish.getOriginalText(), 40));
            Boolean stopOnFailure = (Boolean) chainDef.getOrDefault("stopOnFailure", true);

            List<ChainEntryRequest> entries = new ArrayList<>();
            for (int i = 0; i < workflowIds.size(); i++) {
                entries.add(new ChainEntryRequest(workflowIds.get(i), null, i, stopOnFailure, null));
            }

            CreateChainRequest chainReq = new CreateChainRequest(
                    chainName, "Generated by wish: " + truncate(wish.getOriginalText(), 60),
                    null, entries, stopOnFailure);
            WorkflowChain chain = chainService.create(chainReq);
            wish.setGeneratedChainId(chain.getId());
            log.info("Wish {}: Created chain '{}' ({})", wish.getId(), chainName, chain.getId());
        }

        wish.setIterationCount(wish.getIterationCount() + 1);
        updateStatus(wish, WishStatus.AWAITING_DECOMPOSE_APPROVAL);
    }

    /**
     * Best-effort pre-flight verification: deploys each workflow to the Test instance,
     * runs it, and auto-fixes if it fails. Stores results in wish metadata.
     * Silently skips if the Test instance is not available.
     */
    @SuppressWarnings("unchecked")
    private void verifyGeneratedWorkflows(Wish wish, List<String> workflowIds) {
        if (workflowIds.isEmpty()) return;

        try {
            List<Map<String, String>> results = new ArrayList<>();
            for (String wfId : workflowIds) {
                try {
                    WorkflowFactoryService.FactoryTestResult testResult =
                            workflowFactoryService.verifyAndFix(wfId, 2);
                    results.add(Map.of(
                            "workflowId", wfId,
                            "status", testResult.status(),
                            "error", testResult.error() != null ? testResult.error() : ""
                    ));
                    log.info("Wish {}: Verified workflow {} → {}",
                            wish.getId(), wfId, testResult.status());
                } catch (Exception e) {
                    results.add(Map.of(
                            "workflowId", wfId,
                            "status", "SKIPPED",
                            "error", e.getMessage() != null ? e.getMessage() : "Unknown"
                    ));
                    log.warn("Wish {}: Could not verify workflow {}: {}",
                            wish.getId(), wfId, e.getMessage());
                }
            }

            Map<String, Object> meta = new HashMap<>(wish.getMetadata());
            meta.put("verificationResults", results);
            wish.setMetadata(meta);
        } catch (Exception e) {
            log.warn("Wish {}: Pre-flight verification skipped: {}", wish.getId(), e.getMessage());
        }
    }

    // ── Phase B: Execute ─────────────────────────────────────────

    private void execute(Wish wish) throws Exception {
        log.info("Wish {}: Executing iteration {}", wish.getId(), wish.getIterationCount());

        if (wish.getGeneratedChainId() != null) {
            WorkflowChain chain = chainService.findById(wish.getGeneratedChainId());
            ChainRun chainRun = chainExecutor.execute(chain, Map.of());
            wish.setCurrentRunId(chainRun.getId());
            wishRepository.save(wish);
            broadcastStatus(wish.getId(), wish);

            waitForChainCompletion(chainRun.getId(), wish.getId());
        } else if (!wish.getGeneratedWorkflowIds().isEmpty()) {
            String wfId = wish.getGeneratedWorkflowIds().get(0);
            Workflow workflow = workflowService.findByIdEager(wfId);
            WorkflowRun workflowRun = workflowExecutor.execute(workflow, Map.of());
            wish.setCurrentRunId(workflowRun.getId());
            wishRepository.save(wish);
            broadcastStatus(wish.getId(), wish);

            waitForWorkflowCompletion(workflowRun.getId(), wish.getId());
        } else {
            wish.setStatus(WishStatus.FAILED);
            wish.setError("No workflows were generated during decomposition");
            wishRepository.save(wish);
            broadcastStatus(wish.getId(), wish);
            return;
        }

        // Move to evaluation phase
        wish = findById(wish.getId());
        updateStatus(wish, WishStatus.EVALUATING);
    }

    // ── Phase C: Evaluate ────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private void evaluate(Wish wish) throws Exception {
        log.info("Wish {}: Evaluating results", wish.getId());

        String prompt = buildEvaluatePrompt(wish);
        HostClaudeService.ReasoningResult result = hostClaude.reasonStructured(prompt, null);

        String json = extractJson(result.text());
        Map<String, Object> evaluation = MAPPER.readValue(json, new TypeReference<>() {});

        Boolean satisfied = (Boolean) evaluation.getOrDefault("satisfied", false);
        String summary = (String) evaluation.getOrDefault("summary", "");
        Boolean shouldRetry = (Boolean) evaluation.getOrDefault("shouldRetry", false);

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

        if (Boolean.TRUE.equals(satisfied)) {
            meta.put("pendingEvaluation", evaluation);
            wish.setMetadata(meta);
            wish.setResultSummary(summary);
            updateStatus(wish, WishStatus.AWAITING_APPROVAL);
            log.info("Wish {}: Awaiting user approval — {}", wish.getId(), truncate(summary, 100));
            return;
        } else if (Boolean.TRUE.equals(shouldRetry) && wish.getIterationCount() < wish.getMaxIterations()) {
            wish.setStatus(WishStatus.REFINING);
            wish.setResultSummary(summary);
            log.info("Wish {}: Refining (iteration {}/{})", wish.getId(), wish.getIterationCount(), wish.getMaxIterations());
        } else {
            wish.setStatus(WishStatus.FAILED);
            wish.setResultSummary(summary);
            Object issues = evaluation.getOrDefault("issues", List.of());
            wish.setError("Not satisfied after " + wish.getIterationCount() + " iterations. Issues: " + issues);
            log.info("Wish {}: Failed — {}", wish.getId(), truncate(summary, 100));
        }

        wishRepository.save(wish);
        broadcastStatus(wish.getId(), wish);
    }

    // ── Prompt Building ──────────────────────────────────────────

    private String buildDecomposePrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Wish\n\n");
        sb.append(wish.getOriginalText()).append("\n\n");

        // Add clarification Q&A history
        if (wish.getMetadata() != null && wish.getMetadata().get("clarifications") instanceof List<?> clarifications && !clarifications.isEmpty()) {
            sb.append("## Clarifications from User\n\n");
            for (Object c : clarifications) {
                if (c instanceof Map<?, ?> cMap) {
                    sb.append("**Q:** ").append(cMap.get("question")).append("\n");
                    sb.append("**A:** ").append(cMap.get("answer")).append("\n\n");
                }
            }
        }

        // Add user feedback from refinement
        if (wish.getMetadata() != null && wish.getMetadata().get("userFeedback") instanceof String feedback && !feedback.isBlank()) {
            sb.append("## User Feedback\n\n");
            sb.append(feedback).append("\n\n");
            sb.append("Please adjust the workflow design based on this feedback.\n\n");
        }

        // Add feedback from previous iterations
        if (wish.getMetadata() != null && wish.getMetadata().get("evaluations") instanceof List<?> evals && !evals.isEmpty()) {
            sb.append("## Previous Iteration Feedback\n\n");
            Object lastEval = evals.get(evals.size() - 1);
            sb.append(MAPPER.valueToTree(lastEval).toPrettyString()).append("\n\n");
            sb.append("The previous attempt was not satisfactory. Please adjust the workflow design based on this feedback.\n\n");
        }

        // Add project context
        String projectName = null;
        if (wish.getProjectId() != null) {
            Project project = projectRepository.findById(wish.getProjectId()).orElse(null);
            if (project != null) {
                projectName = project.getName();
                sb.append("## Target Project\n\n");
                sb.append("- **Name**: ").append(project.getName()).append("\n");
                if (project.getPath() != null) {
                    sb.append("- **Path**: ").append(project.getPath()).append("\n");
                }
                if (project.getDescription() != null && !project.getDescription().isBlank()) {
                    sb.append("- **Description**: ").append(project.getDescription()).append("\n");
                }
                sb.append("\n");
            }

            // Add active workflow memories from project workflows
            List<WorkflowMemory> memories = new ArrayList<>();
            for (Workflow wf : workflowRepository.findByProjectId(wish.getProjectId())) {
                memoryRepository.findByWorkflowIdAndActiveTrue(wf.getId()).ifPresent(memories::add);
            }
            if (!memories.isEmpty()) {
                sb.append("## Accumulated Project Knowledge (from previous workflow runs)\n\n");
                for (WorkflowMemory mem : memories) {
                    sb.append("### ").append(mem.getTitle()).append("\n");
                    sb.append(truncate(mem.getContent(), 1000)).append("\n\n");
                }
            }
        }

        // ── Reusable Components (keyword-filtered, cross-project) ────────
        sb.append("## Reusable Components\n\n");
        Set<String> keywords = extractKeywords(wish.getOriginalText());
        Set<String> shownIds = new HashSet<>();

        // 1. Referenced Workflows (user-selected hints) — always shown with details
        if (wish.getReferenceWorkflowIds() != null && !wish.getReferenceWorkflowIds().isEmpty()) {
            sb.append("### Referenced Workflows (user-selected)\n\n");
            for (String refId : wish.getReferenceWorkflowIds()) {
                workflowRepository.findById(refId).ifPresent(wf -> {
                    String pName = wf.getProject() != null ? wf.getProject().getName() : "?";
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId())
                            .append("`, Project: ").append(pName).append("): ");
                    if (wf.getDescription() != null) sb.append(wf.getDescription());
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                });
            }
            sb.append("\n");
        }

        // 2. Current project workflows — always show all
        if (wish.getProjectId() != null) {
            List<Workflow> currentProjectWfs = workflowRepository.findByProjectId(wish.getProjectId());
            if (!currentProjectWfs.isEmpty()) {
                sb.append("### ").append(projectName != null ? projectName : "Current Project")
                        .append(" (current project — use as sub-workflow blocks)\n\n");
                for (Workflow wf : currentProjectWfs) {
                    if (shownIds.contains(wf.getId())) continue;
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId()).append("`)");
                    if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                        sb.append(": ").append(truncate(wf.getDescription(), 120));
                    }
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                }
                sb.append("\n");
            }
        }

        // 3. Other project workflows — keyword-scored, top 10
        List<Workflow> allWorkflows = workflowRepository.findAll();
        List<Workflow> otherProjectWfs = allWorkflows.stream()
                .filter(wf -> !shownIds.contains(wf.getId()))
                .filter(wf -> wish.getProjectId() == null || !wish.getProjectId().equals(
                        wf.getProject() != null ? wf.getProject().getId() : null))
                .toList();

        if (!otherProjectWfs.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<Workflow, Integer>> scored = new ArrayList<>();
            for (Workflow wf : otherProjectWfs) {
                int score = scoreRelevance(keywords, wf.getName(), wf.getDescription(), wf.getBlocks());
                if (score > 0) scored.add(Map.entry(wf, score));
            }
            scored.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            List<Map.Entry<Workflow, Integer>> topCross = scored.stream().limit(10).toList();
            if (!topCross.isEmpty()) {
                sb.append("### Relevant workflows from other projects\n\n");
                for (var entry : topCross) {
                    Workflow wf = entry.getKey();
                    String pName = wf.getProject() != null ? wf.getProject().getName() : "?";
                    sb.append("- **").append(wf.getName()).append("** (`").append(wf.getId())
                            .append("`, Project: ").append(pName).append(")");
                    if (wf.getDescription() != null && !wf.getDescription().isBlank()) {
                        sb.append(": ").append(truncate(wf.getDescription(), 120));
                    }
                    sb.append("\n");
                    sb.append("  Pipeline: ").append(formatBlockPipeline(wf.getBlocks())).append("\n");
                    shownIds.add(wf.getId());
                }
                sb.append("\n");
            }
        }

        // 4. Templates + Pool workflows — keyword-scored, top 10 templates + top 5 pool
        List<WorkflowTemplate> templates = templateRepository.findAllByOrderBySortOrderAsc();
        List<PoolWorkflowEntry> poolEntries = workflowPoolService.listAll();

        List<String> matchingEntries = new ArrayList<>();

        if (!templates.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<WorkflowTemplate, Integer>> scoredTemplates = new ArrayList<>();
            for (WorkflowTemplate tmpl : templates) {
                String tmplName = tmpl.getName().getOrDefault("en",
                        tmpl.getName().values().isEmpty() ? "" : tmpl.getName().values().iterator().next());
                String tmplDesc = tmpl.getDescription() != null
                        ? tmpl.getDescription().getOrDefault("en", "") : "";
                int score = scoreRelevance(keywords, tmplName, tmplDesc, tmpl.getBlocks());
                if (score > 0) scoredTemplates.add(Map.entry(tmpl, score));
            }
            scoredTemplates.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            for (var entry : scoredTemplates.stream().limit(10).toList()) {
                WorkflowTemplate tmpl = entry.getKey();
                String tmplName = tmpl.getName().getOrDefault("en",
                        tmpl.getName().values().iterator().next());
                StringBuilder line = new StringBuilder();
                line.append("- **").append(tmplName).append("** [Template]");
                String tmplDesc = tmpl.getDescription() != null
                        ? tmpl.getDescription().getOrDefault("en", "") : "";
                if (!tmplDesc.isBlank()) {
                    line.append(": ").append(truncate(tmplDesc, 120));
                }
                line.append("\n");
                line.append("  Pipeline: ").append(formatBlockPipeline(tmpl.getBlocks())).append("\n");
                matchingEntries.add(line.toString());
            }
        }

        if (!poolEntries.isEmpty() && !keywords.isEmpty()) {
            List<Map.Entry<PoolWorkflowEntry, Integer>> scoredPool = new ArrayList<>();
            for (PoolWorkflowEntry pe : poolEntries) {
                String peName = pe.name().getOrDefault("en",
                        pe.name().values().isEmpty() ? "" : pe.name().values().iterator().next());
                String peDesc = pe.description() != null
                        ? pe.description().getOrDefault("en", "") : "";
                int score = scoreRelevance(keywords, peName, peDesc, pe.blocks());
                if (score > 0) scoredPool.add(Map.entry(pe, score));
            }
            scoredPool.sort((a, b) -> Integer.compare(b.getValue(), a.getValue()));

            for (var entry : scoredPool.stream().limit(5).toList()) {
                PoolWorkflowEntry pe = entry.getKey();
                String peName = pe.name().getOrDefault("en",
                        pe.name().values().iterator().next());
                StringBuilder line = new StringBuilder();
                line.append("- **").append(peName).append("** [Pool]");
                String peDesc = pe.description() != null
                        ? pe.description().getOrDefault("en", "") : "";
                if (!peDesc.isBlank()) {
                    line.append(": ").append(truncate(peDesc, 120));
                }
                line.append("\n");
                line.append("  Pipeline: ").append(formatBlockPipeline(pe.blocks())).append("\n");
                matchingEntries.add(line.toString());
            }
        }

        if (!matchingEntries.isEmpty()) {
            sb.append("### Matching templates & pool workflows\n\n");
            matchingEntries.forEach(sb::append);
            sb.append("\n");
        }

        // Execution Intelligence from past runs
        sb.append(buildExecutionIntelligence(wish.getProjectId()));

        // Add available skills
        List<Skill> skills = skillRepository.findAll();
        if (!skills.isEmpty()) {
            sb.append("## Available Skills\n\n");
            for (Skill skill : skills) {
                sb.append("- **").append(skill.getName()).append("**: ").append(skill.getDescription()).append("\n");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    // ── Prompt Helpers ──────────────────────────────────────────

    String formatBlockPipeline(List<BlockDefinition> blocks) {
        if (blocks == null || blocks.isEmpty()) return "[empty]";
        return blocks.stream().map(b -> {
            String type = b.getType() != null ? b.getType() : "?";
            Map<String, Object> cfg = b.getConfig();
            String label = "";
            if (cfg != null) {
                if (cfg.containsKey("command")) {
                    label = truncate(String.valueOf(cfg.get("command")), 40);
                } else if (cfg.containsKey("goal")) {
                    String goal = truncate(String.valueOf(cfg.get("goal")), 30);
                    Object maxIter = cfg.get("max_iterations");
                    label = goal + (maxIter != null ? " (" + maxIter + " iter)" : "");
                }
            }
            return label.isEmpty() ? "[" + type + "]" : "[" + type + ": " + label + "]";
        }).collect(Collectors.joining(" → "));
    }

    Set<String> extractKeywords(String text) {
        if (text == null || text.isBlank()) return Set.of();
        return Arrays.stream(WORD_SPLIT.split(text.toLowerCase()))
                .filter(w -> w.length() >= 3)
                .filter(w -> !STOP_WORDS.contains(w))
                .collect(Collectors.toSet());
    }

    int scoreRelevance(Set<String> keywords, String name, String description, List<BlockDefinition> blocks) {
        if (keywords.isEmpty()) return 0;
        int score = 0;
        String nameLower = name != null ? name.toLowerCase() : "";
        String descLower = description != null ? description.toLowerCase() : "";

        for (String kw : keywords) {
            if (nameLower.contains(kw)) score += 3;
            if (descLower.contains(kw)) score += 2;
        }

        // Check block configs for keyword matches
        if (blocks != null) {
            for (BlockDefinition block : blocks) {
                if (block.getConfig() == null) continue;
                String configStr = block.getConfig().values().stream()
                        .filter(Objects::nonNull)
                        .map(String::valueOf)
                        .collect(Collectors.joining(" "))
                        .toLowerCase();
                for (String kw : keywords) {
                    if (configStr.contains(kw)) {
                        score += 1;
                        break; // max +1 per block
                    }
                }
            }
        }
        return score;
    }

    @SuppressWarnings("unchecked")
    private String buildExecutionIntelligence(String projectId) {
        StringBuilder sb = new StringBuilder();
        try {
            List<Workflow> projectWorkflows = projectId != null
                    ? workflowRepository.findByProjectId(projectId)
                    : List.of();

            // Collect per-workflow stats for workflows with enough runs
            List<String> performanceLines = new ArrayList<>();
            double totalCost = 0;
            long totalRuns = 0;
            Set<String> allModels = new LinkedHashSet<>();
            List<String> pitfalls = new ArrayList<>();

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> stats = learningService.getWorkflowStats(wf.getId());
                int runs = ((Number) stats.getOrDefault("totalRuns", 0)).intValue();
                if (runs < 2) continue;

                double successRate = ((Number) stats.getOrDefault("successRate", 0.0)).doubleValue();
                double avgDuration = ((Number) stats.getOrDefault("avgDurationMs", 0.0)).doubleValue();
                totalRuns += runs;

                Map<String, Object> insights = learningService.getExecutionInsights(wf.getId());
                double cost = ((Number) insights.getOrDefault("avgCostPerRunUsd", 0.0)).doubleValue();
                totalCost += ((Number) insights.getOrDefault("totalCostUsd", 0.0)).doubleValue();
                Object models = insights.get("modelsUsed");
                if (models instanceof List<?> modelList) {
                    modelList.forEach(m -> allModels.add(String.valueOf(m)));
                }

                performanceLines.add(String.format(
                        "- **%s** (%d runs): %.0f%% success, avg %.0fs, $%.3f/run",
                        wf.getName(), runs, successRate * 100, avgDuration / 1000.0, cost));

                // Collect pitfalls from high-failure steps
                List<Map<String, Object>> suggestions = learningService.getOptimizationSuggestions(wf.getId());
                for (Map<String, Object> sug : suggestions) {
                    String severity = (String) sug.getOrDefault("severity", "");
                    if ("HIGH".equals(severity)) {
                        pitfalls.add("- " + sug.getOrDefault("message", ""));
                    }
                }
            }

            if (performanceLines.isEmpty() && projectWorkflows.size() < 2) {
                return ""; // Not enough data
            }

            sb.append("## Execution Intelligence (from past runs)\n\n");

            if (!performanceLines.isEmpty()) {
                sb.append("### Workflow Performance\n\n");
                performanceLines.stream().limit(10).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            if (!pitfalls.isEmpty()) {
                sb.append("### Common Issues to Avoid\n\n");
                pitfalls.stream().distinct().limit(5).forEach(l -> sb.append(l).append("\n"));
                sb.append("\n");
            }

            // Agentic block insights (aggregated across project)
            int agenticRunsTotal = 0;
            int goalsAchieved = 0;
            double iterationsSum = 0;
            int iterationsCount = 0;

            for (Workflow wf : projectWorkflows) {
                Map<String, Object> agenticAnalytics = learningService.getAgenticAnalytics(wf.getId());
                int agRuns = ((Number) agenticAnalytics.getOrDefault("totalAgenticRuns", 0)).intValue();
                if (agRuns == 0) continue;
                agenticRunsTotal += agRuns;
                goalsAchieved += ((Number) agenticAnalytics.getOrDefault("achievedGoals", 0)).intValue();
                double avgIter = ((Number) agenticAnalytics.getOrDefault("avgIterationsToGoal", 0.0)).doubleValue();
                if (avgIter > 0) {
                    iterationsSum += avgIter * agRuns;
                    iterationsCount += agRuns;
                }
            }

            if (agenticRunsTotal > 0) {
                double agenticSuccessRate = (double) goalsAchieved / agenticRunsTotal;
                double avgIter = iterationsCount > 0 ? iterationsSum / iterationsCount : 0;
                sb.append("### Agentic Block Insights\n\n");
                sb.append(String.format("- %d agentic runs total, %.0f%% goal achieved\n",
                        agenticRunsTotal, agenticSuccessRate * 100));
                if (avgIter > 0) {
                    sb.append(String.format("- Avg iterations to success: %.1f\n", avgIter));
                }
                sb.append("\n");
            }

            if (totalRuns > 0) {
                sb.append("### Cost Profile\n\n");
                sb.append(String.format("- Total project cost (recent): $%.2f across %d runs\n", totalCost, totalRuns));
                if (!allModels.isEmpty()) {
                    sb.append("- Models used: ").append(String.join(", ", allModels)).append("\n");
                }
                sb.append("\n");
            }

        } catch (Exception e) {
            log.debug("Could not build execution intelligence: {}", e.getMessage());
        }
        return sb.toString();
    }

    private String buildDecomposeSystemPrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();

        // Inject base agent instructions for consistent behavior
        String agentBase = agentInstructionsService.buildInstructionPrefix(null);
        if (!agentBase.isBlank()) {
            sb.append(agentBase).append("\n\n---\n\n");
        }

        sb.append("""
                You are a workflow architect. Your job is to decompose a user's wish into executable workflow blocks.

                ## Available Block Types

                - **simple**: Runs a single Claude CLI prompt. Config: `{"command": "prompt text"}`. Use for tasks that need AI reasoning.
                - **shell**: Runs a shell command. Config: `{"command": "shell command"}`. Use for deterministic tasks (file ops, git, npm, etc.).
                - **agentic**: Multi-turn AI loop with goal. Config: `{"goal": "description", "max_iterations": 10}`. Use for complex tasks requiring exploration and multi-step work.
                - **conditional**: Branching logic. Config: `{"condition": "expression", "then_blocks": [...], "else_blocks": [...]}`.
                - **forEach**: Iterates over items. Config: `{"items": "expression", "variable": "item", "blocks": [...]}`.
                - **retry**: Retries on failure. Config: `{"command": "...", "max_retries": 3}`.
                - **sub-workflow**: Runs an existing workflow as a nested block. Config: `{"workflow_id": "id"}`. Use for reuse.

                ## Design Principles

                1. Prefer **shell** blocks for deterministic tasks (faster, reproducible)
                2. Use **simple** blocks for single AI reasoning steps
                3. Use **agentic** blocks only when the task requires multi-step exploration
                4. Agentic block prompts (goal) must be self-contained — include absolute paths, specific names, and exact instructions
                5. Keep workflows focused — one workflow per coherent task
                6. Use a chain when tasks are logically separate but sequential
                7. Set realistic `max_iterations` for agentic blocks (3-10)
                8. Add shell cleanup steps before agentic blocks if stale data from previous runs could interfere
                9. Use `{{ previous_output }}` in block prompts to reference the output of the previous step
                10. Reference existing workflows via sub-workflow blocks instead of duplicating logic — check "Reusable Components" for workflows from ALL projects, not just the current one
                11. Workflows from other projects can be reused as sub-workflows — use their ID in the `workflow_id` config field
                12. User-selected "Referenced Workflows" should be prioritized for reuse when they match the task

                ## Output Format

                Respond with ONLY a JSON object:
                ```json
                {
                  "analysis": "Brief analysis of the wish",
                  "strategy": "Chosen implementation strategy",
                  "workflows": [
                    {
                      "name": "Workflow Name",
                      "description": "What this workflow does",
                      "blocks": [
                        {"type": "shell", "config": {"command": "npm run lint"}},
                        {"type": "agentic", "config": {"goal": "Fix all lint errors", "max_iterations": 5}}
                      ]
                    }
                  ],
                  "chain": {
                    "name": "Chain Name",
                    "stopOnFailure": true
                  },
                  "needsClarification": false,
                  "clarificationQuestion": null
                }
                ```

                If the wish is too vague, set `needsClarification: true` and provide a `clarificationQuestion`.
                The `chain` field is only needed when generating multiple workflows.

                CRITICAL: Your ENTIRE response must be a single JSON object. No text before or after. No markdown fences. No explanations. Just the raw JSON object starting with { and ending with }.
                """);

        return sb.toString();
    }

    private String buildEvaluatePrompt(Wish wish) {
        StringBuilder sb = new StringBuilder();
        sb.append("## Original Wish\n\n");
        sb.append(wish.getOriginalText()).append("\n\n");

        sb.append("## Generated Workflow Structure\n\n");
        if (wish.getDecomposition() != null) {
            sb.append(MAPPER.valueToTree(wish.getDecomposition()).toPrettyString()).append("\n\n");
        }

        sb.append("## Execution Results\n\n");

        if (wish.getGeneratedChainId() != null && wish.getCurrentRunId() != null) {
            // Chain run results
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
            // Single workflow run results
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
                        sb.append(" (").append(truncate(String.valueOf(err), 100)).append(")");
                    }
                    sb.append("\n");
                }
            }
            sb.append("\n");
        }

        sb.append("\n## Task\n\n");
        sb.append("""
                Evaluate whether the wish has been fulfilled.

                CRITICAL: Your ENTIRE response must be a single JSON object. No text before or after. No markdown fences. No explanations. Just the raw JSON object starting with { and ending with }.

                {
                  "satisfied": true/false,
                  "summary": "What was achieved",
                  "issues": ["Issue 1", "Issue 2"],
                  "suggestions": ["Improvement 1", "Improvement 2"],
                  "shouldRetry": true/false
                }
                """);

        return sb.toString();
    }

    private void appendWorkflowRunOutput(StringBuilder sb, String runId) {
        workflowRunRepository.findById(runId).ifPresent(run -> {
            sb.append("  Status: ").append(run.getStatus()).append("\n");
            List<StepResult> steps = stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc(runId);
            for (StepResult step : steps) {
                String blockType = step.getMetadata() != null
                        ? (String) step.getMetadata().getOrDefault("blockType", "unknown")
                        : "unknown";
                sb.append("  Step ").append(step.getStepIndex()).append(" (").append(blockType).append("): ");
                sb.append(step.getStatus()).append("\n");
                if (step.getOutput() != null) {
                    sb.append("    Output: ").append(truncate(step.getOutput(), 500)).append("\n");
                }
            }
        });
    }

    // ── Helpers ──────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    private List<BlockDefinition> parseBlocks(Object blocksObj) {
        List<BlockDefinition> blocks = new ArrayList<>();
        if (!(blocksObj instanceof List<?> blockList)) return blocks;

        for (Object bObj : blockList) {
            if (!(bObj instanceof Map<?, ?> bMap)) continue;
            Map<String, Object> blockMap = (Map<String, Object>) bMap;
            String type = (String) blockMap.getOrDefault("type", "simple");
            Map<String, Object> config = blockMap.get("config") instanceof Map<?, ?> c
                    ? new LinkedHashMap<>((Map<String, Object>) c)
                    : new LinkedHashMap<>();
            blocks.add(BlockDefinition.builder().type(type).config(config).build());
        }
        return blocks;
    }

    private void waitForWorkflowCompletion(String runId, String wishId) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(WORKFLOW_WAIT_MINUTES));
        while (Instant.now().isBefore(deadline)) {
            if (isCancelled(wishId)) return;
            WorkflowRun run = workflowRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return;
            }
            sleep();
        }
        log.warn("Wish {}: Workflow {} timed out", wishId, runId);
    }

    private void waitForChainCompletion(String runId, String wishId) {
        Instant deadline = Instant.now().plus(Duration.ofMinutes(WORKFLOW_WAIT_MINUTES));
        while (Instant.now().isBefore(deadline)) {
            if (isCancelled(wishId)) return;
            ChainRun run = chainRunRepository.findById(runId).orElseThrow();
            if (run.getStatus() != RunStatus.RUNNING && run.getStatus() != RunStatus.PENDING) {
                return;
            }
            sleep();
        }
        log.warn("Wish {}: Chain {} timed out", wishId, runId);
    }

    private boolean isCancelled(String wishId) {
        AtomicBoolean flag = activeCancellations.get(wishId);
        return flag != null && flag.get();
    }

    private void updateStatus(Wish wish, WishStatus status) {
        wish.setStatus(status);
        wishRepository.save(wish);
        broadcastStatus(wish.getId(), wish);
    }

    private boolean isTerminal(WishStatus status) {
        return status == WishStatus.COMPLETED || status == WishStatus.FAILED || status == WishStatus.CANCELLED;
    }

    private boolean isPaused(WishStatus status) {
        return status == WishStatus.AWAITING_APPROVAL
                || status == WishStatus.AWAITING_CLARIFICATION
                || status == WishStatus.AWAITING_DECOMPOSE_APPROVAL;
    }

    private void broadcastStatus(String wishId, Wish wish) {
        try {
            Map<String, Object> payload = Map.of(
                    "wishId", wishId,
                    "status", wish.getStatus().name(),
                    "iterationCount", wish.getIterationCount(),
                    "maxIterations", wish.getMaxIterations()
            );
            messagingTemplate.convertAndSend("/topic/wishes/" + wishId + "/status", payload);
        } catch (Exception e) {
            log.debug("Failed to broadcast wish status", e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(POLLING_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String extractJson(String text) {
        if (text == null) return "{}";

        // Strip markdown code fences if present
        String cleaned = text;
        int fenceStart = cleaned.indexOf("```");
        if (fenceStart >= 0) {
            int contentStart = cleaned.indexOf('\n', fenceStart);
            int fenceEnd = cleaned.indexOf("```", contentStart > 0 ? contentStart : fenceStart + 3);
            if (contentStart > 0 && fenceEnd > contentStart) {
                cleaned = cleaned.substring(contentStart + 1, fenceEnd).trim();
            }
        }

        // Find first { and last } to extract JSON
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return cleaned.substring(start, end + 1);
        }

        // No JSON found — log the raw response for debugging
        log.warn("No JSON object found in Claude response (first 200 chars): {}",
                truncate(text, 200));
        throw new IllegalStateException("Claude did not return valid JSON. Response: " + truncate(text, 300));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

}
