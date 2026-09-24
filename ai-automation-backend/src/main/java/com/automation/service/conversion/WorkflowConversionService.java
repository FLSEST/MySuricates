package com.automation.service.conversion;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.dto.response.WorkflowResponse;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

@Service
@Slf4j
public class WorkflowConversionService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final AiProviderRegistry aiProviderRegistry;
    private final AgentInstructionsService agentInstructionsService;
    private final TransactionTemplate transactionTemplate;
    private final TransactionTemplate readOnlyTransactionTemplate;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public WorkflowConversionService(WorkflowRunRepository runRepository,
                                     WorkflowService workflowService,
                                     WorkflowMapper workflowMapper,
                                     AiProviderRegistry aiProviderRegistry,
                                     AgentInstructionsService agentInstructionsService,
                                     PlatformTransactionManager transactionManager) {
        this.runRepository = runRepository;
        this.workflowService = workflowService;
        this.workflowMapper = workflowMapper;
        this.aiProviderRegistry = aiProviderRegistry;
        this.agentInstructionsService = agentInstructionsService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate = new TransactionTemplate(transactionManager);
        this.readOnlyTransactionTemplate.setReadOnly(true);
    }

    /**
     * Deliberately not {@code @Transactional}: the AI-assisted conversion asks the model, and with
     * HikariCP pool-size 1 an enclosing transaction would hold the only DB connection for that whole
     * call. The run is read in a short read-only burst before it.
     */
    public WorkflowSuggestion suggestWorkflow(String runId) {
        List<StepResult> agenticSteps =
                readOnlyTransactionTemplate.execute(status -> goalAchievedAgenticSteps(runId));
        if (agenticSteps == null || agenticSteps.isEmpty()) {
            return WorkflowSuggestion.notAvailable();
        }

        // Try AI-assisted conversion first, fall back to rule-based
        try {
            WorkflowSuggestion aiSuggestion = suggestWorkflowAI(agenticSteps);
            if (aiSuggestion.available()) {
                return aiSuggestion;
            }
        } catch (Exception e) {
            log.debug("AI-assisted conversion failed, falling back to rule-based: {}", e.getMessage());
        }

        return suggestWorkflowRuleBased(agenticSteps);
    }

    /**
     * Read burst of {@link #suggestWorkflow}: the agentic steps of a completed run that reached their
     * goal, copied out of the lazy {@code steps} collection before the transaction closes (OSIV is
     * off). Empty when the run did not complete.
     */
    private List<StepResult> goalAchievedAgenticSteps(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        if (run.getStatus() != RunStatus.COMPLETED) {
            return List.of();
        }

        return run.getSteps().stream()
                .filter(s -> s.getMetadata() != null
                        && "agentic".equals(s.getMetadata().get("blockType"))
                        && Boolean.TRUE.equals(s.getMetadata().get("goalAchieved")))
                .toList();
    }

    @SuppressWarnings("unchecked")
    private WorkflowSuggestion suggestWorkflowRuleBased(List<StepResult> agenticSteps) {
        List<BlockDefinition> blocks = new ArrayList<>();
        String goal = "";
        int totalActionsAnalyzed = 0;

        for (StepResult step : agenticSteps) {
            Map<String, Object> meta = step.getMetadata();
            goal = (String) meta.getOrDefault("goal", "");

            Object iterationsObj = meta.get("iterations");
            if (!(iterationsObj instanceof List<?> iterList)) continue;

            for (Object iterObj : iterList) {
                if (!(iterObj instanceof Map<?, ?> iterMap)) continue;
                Map<String, Object> iter = (Map<String, Object>) iterMap;

                String action = (String) iter.get("action");
                if (action == null || action.isEmpty()) continue;

                Integer exitCode = iter.get("exitCode") instanceof Number n ? n.intValue() : null;
                if (exitCode != null && exitCode != 0) continue;

                // Each successful action becomes a simple block
                Map<String, Object> config = new LinkedHashMap<>();
                config.put("command", action);
                config.put("on_failure", "stop");

                BlockDefinition block = BlockDefinition.builder()
                        .type("simple")
                        .config(config)
                        .build();
                blocks.add(block);
                totalActionsAnalyzed++;
            }
        }

        if (blocks.isEmpty()) {
            return WorkflowSuggestion.notAvailable();
        }

        String name = "Auto: " + truncate(goal, 60);
        String description = "Automatically generated from successful agentic run. " +
                "Original goal: " + goal;

        return new WorkflowSuggestion(name, description, blocks, goal, totalActionsAnalyzed, true);
    }

    @SuppressWarnings("unchecked")
    private WorkflowSuggestion suggestWorkflowAI(List<StepResult> agenticSteps) throws Exception {
        // Build action sequence description for the AI
        StringBuilder actionDesc = new StringBuilder();
        String goal = "";

        for (StepResult step : agenticSteps) {
            Map<String, Object> meta = step.getMetadata();
            goal = (String) meta.getOrDefault("goal", "");
            actionDesc.append("Goal: ").append(goal).append("\n\n");

            Object iterationsObj = meta.get("iterations");
            if (!(iterationsObj instanceof List<?> iterList)) continue;

            int i = 0;
            for (Object iterObj : iterList) {
                if (!(iterObj instanceof Map<?, ?> iterMap)) continue;
                Map<String, Object> iter = (Map<String, Object>) iterMap;
                i++;
                String action = (String) iter.get("action");
                if (action == null || action.isEmpty()) continue;
                Integer exitCode = iter.get("exitCode") instanceof Number n ? n.intValue() : null;
                actionDesc.append("Step ").append(i).append(": ").append(action);
                if (exitCode != null) actionDesc.append(" (exit: ").append(exitCode).append(")");
                actionDesc.append("\n");
            }
        }

        String prompt = WorkflowConversionPrompt.build(actionDesc.toString());

        String systemPrompt = agentInstructionsService.buildInstructionPrefix(null);
        AiProvider.ReasoningResult aiResult = aiProviderRegistry.getDefault().reason(
                new AiProvider.ReasoningRequest(prompt, systemPrompt));
        String response = aiResult.text();

        // Extract JSON from response
        String json = extractJson(response);
        Map<String, Object> parsed = JsonRepairUtil.parseWithRepair(json, new TypeReference<>() {});

        String sugName = (String) parsed.getOrDefault("suggestedName", "Auto: " + truncate(goal, 60));
        String sugDesc = (String) parsed.getOrDefault("suggestedDescription", "");

        List<BlockDefinition> blocks = new ArrayList<>();
        Object blocksObj = parsed.get("blocks");
        if (blocksObj instanceof List<?> blockList) {
            for (Object bObj : blockList) {
                if (bObj instanceof Map<?, ?> bMap) {
                    Map<String, Object> blockMap = (Map<String, Object>) bMap;
                    String type = (String) blockMap.getOrDefault("type", "simple");
                    Map<String, Object> config = blockMap.get("config") instanceof Map<?, ?> c
                            ? (Map<String, Object>) c
                            : new LinkedHashMap<>();
                    blocks.add(BlockDefinition.builder().type(type).config(config).build());
                }
            }
        }

        if (blocks.isEmpty()) {
            return WorkflowSuggestion.notAvailable();
        }

        return new WorkflowSuggestion(sugName, sugDesc, blocks, goal, blocks.size(), true);
    }

    /**
     * Not {@code @Transactional} either, for the model call inside {@link #suggestWorkflow}: only
     * creating the workflow and mapping the response share one transaction, after that call.
     */
    public WorkflowResponse createWorkflowFromSuggestion(String runId, String projectId, String name) {
        WorkflowSuggestion suggestion = suggestWorkflow(runId);

        if (!suggestion.available()) {
            throw new IllegalStateException("No conversion available for run " + runId);
        }

        String workflowName = name != null && !name.isBlank() ? name : suggestion.suggestedName();

        CreateWorkflowRequest request = new CreateWorkflowRequest(
                projectId,
                workflowName,
                suggestion.suggestedDescription(),
                null,
                suggestion.blocks(),
                List.of(),
                null,
                null,
                null,
                null,
                null,
                null,
                null
        );

        return transactionTemplate.execute(status ->
                workflowMapper.toResponse(workflowService.create(request, ChangeSource.CONVERSION)));
    }

    private String extractJson(String text) {
        // Find first { and last } to extract JSON
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return text;
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }
}
