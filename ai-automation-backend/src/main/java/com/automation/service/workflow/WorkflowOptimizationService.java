package com.automation.service.workflow;

import com.automation.entity.Workflow;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowOptimizationService {

    private final WorkflowService workflowService;
    private final AiProviderRegistry aiProviderRegistry;
    private final ObjectMapper objectMapper;

    public Map<String, Object> optimizeWorkflow(String workflowId) {
        Workflow workflow = workflowService.findById(workflowId);

        String blocksJson;
        try {
            blocksJson = objectMapper.writeValueAsString(workflow.getBlocks());
        } catch (JsonProcessingException e) {
            blocksJson = workflow.getBlocks().toString();
        }

        String prompt = WorkflowOptimizationPrompt.build(workflow, blocksJson);

        try {
            AiProvider provider = aiProviderRegistry.resolve(workflow.getProvider());
            AiProvider.ReasoningResult result = provider.reason(new AiProvider.ReasoningRequest(prompt));
            return parseResponse(result.text());
        } catch (IOException e) {
            log.error("Workflow optimization failed for {}: {}", workflowId, e.getMessage());
            return Map.of(
                    "error", true,
                    "message", "Optimization failed: " + e.getMessage()
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponse(String response) {
        // Try to extract JSON from the response
        String json = response.trim();

        // Handle markdown code blocks
        if (json.contains("```json")) {
            int start = json.indexOf("```json") + 7;
            int end = json.indexOf("```", start);
            if (end > start) {
                json = json.substring(start, end).trim();
            }
        } else if (json.contains("```")) {
            int start = json.indexOf("```") + 3;
            int end = json.indexOf("```", start);
            if (end > start) {
                json = json.substring(start, end).trim();
            }
        }

        try {
            return JsonRepairUtil.parseWithRepair(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Could not parse optimization response as JSON: {}", e.getMessage());
            return Map.of(
                    "suggestedName", "",
                    "suggestedDescription", "",
                    "suggestedInstructions", "",
                    "blocks", List.of(),
                    "changes", List.of(Map.of("type", "error", "description", "Could not parse AI response")),
                    "rawResponse", response
            );
        }
    }
}
