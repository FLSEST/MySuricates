package com.automation.service.conversion;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.dto.response.WorkflowResponse;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.ai.HostClaudeService;
import com.automation.service.workflow.WorkflowService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowConversionService {

    private final WorkflowRunRepository runRepository;
    private final WorkflowService workflowService;
    private final WorkflowMapper workflowMapper;
    private final HostClaudeService hostClaude;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Transactional(readOnly = true)
    public WorkflowSuggestion suggestWorkflow(String runId) {
        WorkflowRun run = runRepository.findById(runId)
                .orElseThrow(() -> new ResourceNotFoundException("WorkflowRun", runId));

        if (run.getStatus() != RunStatus.COMPLETED) {
            return WorkflowSuggestion.notAvailable();
        }

        // Find agentic steps with iteration data
        List<StepResult> agenticSteps = run.getSteps().stream()
                .filter(s -> s.getMetadata() != null
                        && "agentic".equals(s.getMetadata().get("blockType"))
                        && Boolean.TRUE.equals(s.getMetadata().get("goalAchieved")))
                .toList();

        if (agenticSteps.isEmpty()) {
            return WorkflowSuggestion.notAvailable();
        }

        // Try AI-assisted conversion first, fall back to rule-based
        try {
            WorkflowSuggestion aiSuggestion = suggestWorkflowAI(run, agenticSteps);
            if (aiSuggestion.available()) {
                return aiSuggestion;
            }
        } catch (Exception e) {
            log.debug("AI-assisted conversion failed, falling back to rule-based: {}", e.getMessage());
        }

        return suggestWorkflowRuleBased(run, agenticSteps);
    }

    @SuppressWarnings("unchecked")
    private WorkflowSuggestion suggestWorkflowRuleBased(WorkflowRun run, List<StepResult> agenticSteps) {
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
    private WorkflowSuggestion suggestWorkflowAI(WorkflowRun run, List<StepResult> agenticSteps) throws Exception {
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

        String prompt = """
                Analyze this successful agentic execution and convert it into optimized workflow blocks.

                %s

                Output ONLY a JSON object with these fields:
                {
                  "suggestedName": "short name for the workflow",
                  "suggestedDescription": "brief description",
                  "blocks": [
                    {"type": "simple", "config": {"command": "...", "on_failure": "stop"}},
                    {"type": "retry", "config": {"command": "...", "max_retries": 3, "on_failure": "stop"}}
                  ]
                }

                Rules:
                - Use "simple" blocks for straightforward commands
                - Use "retry" blocks for commands that might fail transiently
                - Use "conditional" blocks if the sequence has clear if-then logic
                - Merge related steps when possible
                - Remove redundant or exploratory steps
                - Keep only the essential action chain
                - Output ONLY valid JSON, no explanations
                """.formatted(actionDesc);

        String response = hostClaude.reason(prompt);

        // Extract JSON from response
        String json = extractJson(response);
        Map<String, Object> parsed = MAPPER.readValue(json, new TypeReference<>() {});

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

    @Transactional
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
                List.of()
        );

        Workflow created = workflowService.create(request);
        return workflowMapper.toResponse(created);
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
