package com.automation.service.factory;

import com.automation.entity.BlockDefinition;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Turns the text the generator model returns into workflow structures: the workflow JSON
 * (with or without markdown fences), its block list, the auto-fix block array, and the error
 * of a failed test run.
 *
 * <p>Extracted from {@code WorkflowFactoryService} (Audit-TODO 3b0a021c). Both block entry
 * points share {@link #toBlockDefinition}, so a direct array and a {@code {"blocks": [...]}}
 * envelope map the same fields (TODO ba5df025).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactoryResponseParser {

    private final ObjectMapper objectMapper;

    public JsonNode extractJsonFromResponse(String response) throws IOException {
        // Try to find JSON object in the response
        String trimmed = stripFences(response.trim());

        // Find first { and last }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = trimmed.substring(start, end + 1);
            return JsonRepairUtil.readTreeWithRepair(json);
        }

        throw new IOException("No valid JSON found in Claude response");
    }

    public List<BlockDefinition> parseBlocks(JsonNode workflowJson) {
        List<BlockDefinition> blocks = new ArrayList<>();
        JsonNode blocksNode = workflowJson.path("blocks");
        if (blocksNode.isArray()) {
            for (JsonNode blockNode : blocksNode) {
                blocks.add(toBlockDefinition(blockNode));
            }
        }
        return blocks;
    }

    /** Maps one block node: type (default {@code simple}), config and requiredCapabilities. */
    private BlockDefinition toBlockDefinition(JsonNode blockNode) {
        BlockDefinition block = new BlockDefinition();
        block.setType(blockNode.path("type").asText("simple"));
        if (blockNode.has("config")) {
            Map<String, Object> config = objectMapper.convertValue(
                    blockNode.get("config"), new TypeReference<>() {});
            block.setConfig(config);
        }
        if (blockNode.has("requiredCapabilities")) {
            List<String> caps = objectMapper.convertValue(
                    blockNode.get("requiredCapabilities"), new TypeReference<>() {});
            block.setRequiredCapabilities(caps);
        }
        return block;
    }

    /** Removes a surrounding markdown code fence (```lang ... ```), if present. */
    private static String stripFences(String trimmed) {
        if (trimmed.startsWith("```")) {
            int firstNewline = trimmed.indexOf('\n');
            int lastFence = trimmed.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                return trimmed.substring(firstNewline + 1, lastFence).trim();
            }
        }
        return trimmed;
    }

    public List<BlockDefinition> parseBlocksFromArray(String response) {
        try {
            String trimmed = stripFences(response.trim());

            // Find JSON array or object
            int arrStart = trimmed.indexOf('[');
            int objStart = trimmed.indexOf('{');

            if (arrStart >= 0 && (objStart < 0 || arrStart < objStart)) {
                // Direct array
                int arrEnd = trimmed.lastIndexOf(']');
                String json = trimmed.substring(arrStart, arrEnd + 1);
                JsonNode arrayNode = JsonRepairUtil.readTreeWithRepair(json);
                List<BlockDefinition> blocks = new ArrayList<>();
                for (JsonNode blockNode : arrayNode) {
                    blocks.add(toBlockDefinition(blockNode));
                }
                return blocks;
            } else if (objStart >= 0) {
                // Wrapped in object with "blocks" key
                JsonNode obj = extractJsonFromResponse(trimmed);
                return parseBlocks(obj);
            }
        } catch (Exception e) {
            log.warn("Failed to parse blocks from auto-fix response: {}", e.getMessage());
        }
        return List.of();
    }

    public String extractStepErrors(JsonNode run) {
        JsonNode steps = run.path("steps");
        if (steps.isArray()) {
            for (JsonNode step : steps) {
                if ("FAILED".equals(step.path("status").asText())) {
                    String error = step.path("error").asText(null);
                    if (error != null) return error;
                    String output = step.path("output").asText(null);
                    if (output != null && output.length() < 500) return output;
                }
            }
        }
        return null;
    }
}
