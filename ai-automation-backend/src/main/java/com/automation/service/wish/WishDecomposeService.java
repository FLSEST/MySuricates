package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.dto.request.ChainEntryRequest;
import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.chain.ChainService;
import com.automation.service.factory.FactoryTestResult;
import com.automation.service.factory.FactoryVerificationService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.util.BlockJsonReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;

@Service
@Slf4j
public class WishDecomposeService {

    private final WishConfig wishConfig;
    private final WishRepository wishRepository;
    private final AiProviderRegistry aiProviderRegistry;
    private final ProjectRepository projectRepository;
    private final WorkflowService workflowService;
    private final ChainService chainService;
    private final FactoryVerificationService factoryVerificationService;
    private final WishPromptBuilder promptBuilder;
    private final SimpMessagingTemplate messagingTemplate;
    private final GeneratedWorkflowCheck buildCheck;

    public WishDecomposeService(
            WishConfig wishConfig,
            WishRepository wishRepository,
            AiProviderRegistry aiProviderRegistry,
            ProjectRepository projectRepository,
            WorkflowService workflowService,
            ChainService chainService,
            @Lazy FactoryVerificationService factoryVerificationService,
            WishPromptBuilder promptBuilder,
            SimpMessagingTemplate messagingTemplate,
            GeneratedWorkflowCheck buildCheck
    ) {
        this.wishConfig = wishConfig;
        this.wishRepository = wishRepository;
        this.aiProviderRegistry = aiProviderRegistry;
        this.projectRepository = projectRepository;
        this.workflowService = workflowService;
        this.chainService = chainService;
        this.factoryVerificationService = factoryVerificationService;
        this.promptBuilder = promptBuilder;
        this.messagingTemplate = messagingTemplate;
        this.buildCheck = buildCheck;
    }

    @SuppressWarnings("unchecked")
    public void decompose(Wish wish) throws Exception {
        log.info("Wish {}: Decomposing '{}'", wish.getId(), WishStringUtil.truncate(wish.getOriginalText(), 80));

        String prompt = promptBuilder.buildDecomposePrompt(wish);
        String systemPrompt = promptBuilder.buildDecomposeSystemPrompt(wish);

        String workingDir = null;
        if (wish.getProjectId() != null) {
            workingDir = projectRepository.findById(wish.getProjectId())
                    .map(Project::getPath)
                    .orElse(null);
        }

        AiProvider defaultProvider = aiProviderRegistry.getDefault();
        AiProvider.ReasoningResult result = defaultProvider.reason(
                new AiProvider.ReasoningRequest(prompt, systemPrompt, workingDir, 3, null));

        Map<String, Object> parsed;
        try {
            parsed = WishJsonUtil.parseJsonSafely(result.text(), "decompose");
        } catch (IllegalStateException e) {
            log.warn("Wish {}: Decompose JSON parse failed, retrying with shorter prompt", wish.getId());
            String retryPrompt = prompt + "\n\nIMPORTANT: Keep your JSON response SHORT. "
                    + "Max 3 workflows, max 5 blocks per workflow. Respond ONLY with valid JSON, no markdown.";
            AiProvider.ReasoningResult retryResult = defaultProvider.reason(
                    new AiProvider.ReasoningRequest(retryPrompt, systemPrompt, workingDir, 3, null));
            parsed = WishJsonUtil.parseJsonSafely(retryResult.text(), "decompose-retry");
        }

        wish.setDecomposition(parsed);

        // Snapshot decomposition into iteration history
        Map<String, Object> iterationEntry = new HashMap<>();
        iterationEntry.put("iteration", wish.getIterationCount() + 1);
        iterationEntry.put("phase", "decompose");
        iterationEntry.put("timestamp", Instant.now().toString());
        iterationEntry.put("decomposition", new HashMap<>(parsed));
        List<Map<String, Object>> history = new ArrayList<>(wish.getIterationHistory());
        history.add(iterationEntry);
        wish.setIterationHistory(history);

        // Check if clarification is needed
        Boolean needsClarification = (Boolean) parsed.get("needsClarification");
        if (Boolean.TRUE.equals(needsClarification)) {
            String question = (String) parsed.getOrDefault("clarificationQuestion", "Could you provide more details?");
            Map<String, Object> meta = new HashMap<>(wish.getMetadata());
            meta.put("clarificationQuestion", question);
            wish.setMetadata(meta);
            WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, WishStatus.AWAITING_CLARIFICATION);
            return;
        }

        // Create workflows from decomposition
        List<String> workflowIds = new ArrayList<>();
        final String dir = workingDir;
        Object workflowsObj = parsed.get("workflows");
        if (workflowsObj instanceof List<?> workflowList) {
            for (Object wfObj : workflowList) {
                if (!(wfObj instanceof Map<?, ?> wfMap)) continue;
                Map<String, Object> wf = (Map<String, Object>) wfMap;

                String name = (String) wf.getOrDefault("name", "Wish-Workflow");
                String description = (String) wf.getOrDefault("description", "");

                List<BlockDefinition> blocks = parseBlocks(wf.get("blocks"));

                // Build check (TODO 326d5987): HIGH findings get exactly one correction round with the
                // decompose call's provider and system prompt; the default timeouts apply to the answer too.
                GeneratedWorkflowCheck.Correction check = buildCheck.correctOnce(blocks, null,
                        correctionPrompt -> defaultProvider.reason(new AiProvider.ReasoningRequest(
                                correctionPrompt, systemPrompt, dir, 3, null)).text());
                blocks = check.blocks();
                if (check.applied()) {
                    blocks.forEach(b -> applyDefaultTimeouts(b.getType(), b.getConfig()));
                }

                String model = (String) wf.getOrDefault("model", null);
                if (model == null) {
                    model = promptBuilder.inferModelFromBlocks(blocks, description);
                }

                CreateWorkflowRequest req = new CreateWorkflowRequest(
                        wish.getProjectId(),
                        name,
                        description,
                        null,
                        blocks,
                        List.of(),
                        null,
                        model,
                        null,
                        null,
                        null,
                        null,
                        null
                );

                Workflow created = workflowService.create(req, ChangeSource.WISH);
                workflowIds.add(created.getId());
                log.info("Wish {}: Created workflow '{}' ({})", wish.getId(), name, created.getId());
                buildCheck.reportRemaining(created.getId(), name, "Wunsch", check.remaining());
            }
        }

        wish.setGeneratedWorkflowIds(workflowIds);

        // Add workflowIds to current iteration entry
        WishStatusHelper.updateCurrentIterationEntry(wish, "workflowIds", new ArrayList<>(workflowIds));

        // Pre-flight verification on Test instance (best effort)
        verifyGeneratedWorkflows(wish, workflowIds);

        // Create chain if multiple workflows
        Object chainObj = parsed.get("chain");
        if (workflowIds.size() > 1 && chainObj instanceof Map<?, ?> chainMap) {
            Map<String, Object> chainDef = (Map<String, Object>) chainMap;
            String chainName = (String) chainDef.getOrDefault("name", "Wish-Chain: " + WishStringUtil.truncate(wish.getOriginalText(), 40));
            Boolean stopOnFailure = (Boolean) chainDef.getOrDefault("stopOnFailure", true);

            List<ChainEntryRequest> entries = new ArrayList<>();
            for (int i = 0; i < workflowIds.size(); i++) {
                entries.add(new ChainEntryRequest(workflowIds.get(i), null, i, stopOnFailure, null));
            }

            CreateChainRequest chainReq = new CreateChainRequest(
                    chainName, "Generated by wish: " + WishStringUtil.truncate(wish.getOriginalText(), 60),
                    null, entries, stopOnFailure);
            WorkflowChain chain = chainService.create(chainReq);
            wish.setGeneratedChainId(chain.getId());
            log.info("Wish {}: Created chain '{}' ({})", wish.getId(), chainName, chain.getId());
        }

        wish.setIterationCount(wish.getIterationCount() + 1);
        WishStatus nextStatus = wish.isAutoRefine() ? WishStatus.EXECUTING : WishStatus.AWAITING_DECOMPOSE_APPROVAL;
        if (wish.isAutoRefine()) {
            log.info("Wish {}: Auto-approving decomposition (autoRefine) — proceeding to execute", wish.getId());
        }
        WishStatusHelper.updateStatus(wishRepository, messagingTemplate, wish, nextStatus);
    }

    @SuppressWarnings("unchecked")
    private void verifyGeneratedWorkflows(Wish wish, List<String> workflowIds) {
        if (workflowIds.isEmpty()) return;

        try {
            List<Map<String, String>> results = new ArrayList<>();
            for (String wfId : workflowIds) {
                try {
                    FactoryTestResult testResult =
                            factoryVerificationService.verifyAndFix(wfId, 2);
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

    /** The blocks of one decomposed workflow ({@link BlockJsonReader#fromMapList}) with the default timeouts. */
    private List<BlockDefinition> parseBlocks(Object blocksObj) {
        List<BlockDefinition> blocks = BlockJsonReader.fromMapList(blocksObj);
        blocks.forEach(b -> applyDefaultTimeouts(b.getType(), b.getConfig()));
        return blocks;
    }

    private void applyDefaultTimeouts(String type, Map<String, Object> config) {
        switch (type) {
            case "shell" -> config.putIfAbsent("timeout_seconds", wishConfig.getDefaultShellTimeoutSeconds());
            case "agentic" -> {
                config.putIfAbsent("timeout_seconds", wishConfig.getDefaultAgenticTimeoutSeconds());
                config.putIfAbsent("iteration_timeout_seconds", wishConfig.getDefaultAgenticIterationTimeoutSeconds());
                config.putIfAbsent("total_timeout_seconds", wishConfig.getDefaultAgenticTotalTimeoutSeconds());
            }
            case "simple" -> config.putIfAbsent("timeout_seconds", wishConfig.getDefaultShellTimeoutSeconds());
            default -> { /* no defaults for conditional, foreach, etc. */ }
        }
    }
}
