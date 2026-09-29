package com.automation.service.factory;

import com.automation.entity.BlockDefinition;
import com.automation.util.BlockJsonReader;
import com.automation.util.JsonRepairUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns the text the generator model returns into workflow structures: the workflow JSON
 * (with or without markdown fences), its block list, the auto-fix block array, and the error
 * of a failed test run.
 *
 * <p>Extracted from {@code WorkflowFactoryService} (Audit-TODO 3b0a021c). Both block entry
 * points map each block with {@link BlockJsonReader#toBlock} — the same mapping the build-check
 * correction uses — so a direct array and a {@code {"blocks": [...]}} envelope keep the same fields,
 * {@code name} and {@code skillIds} included (TODOs ba5df025, d6be36f3).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class FactoryResponseParser {

    private final ObjectMapper objectMapper;

    public JsonNode extractJsonFromResponse(String response) throws IOException {
        // Try to find JSON object in the response
        String trimmed = BlockJsonReader.stripFences(response.trim());

        // Find first { and last }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            String json = trimmed.substring(start, end + 1);
            return JsonRepairUtil.readTreeWithRepair(json);
        }

        throw new IOException("No valid JSON found in Claude response");
    }

    /** The {@code blocks} array of a generated workflow, mapped by {@link BlockJsonReader#toBlock}. */
    public List<BlockDefinition> parseBlocks(JsonNode workflowJson) {
        List<BlockDefinition> blocks = new ArrayList<>();
        JsonNode blocksNode = workflowJson.path("blocks");
        if (blocksNode.isArray()) {
            for (JsonNode blockNode : blocksNode) {
                blocks.add(BlockJsonReader.toBlock(blockNode, objectMapper));
            }
        }
        return blocks;
    }

    /**
     * The block list of an auto-fix answer: a JSON array or an object with {@code blocks}
     * ({@link BlockJsonReader#parseAnswer}). Empty if the answer holds nothing usable.
     */
    public List<BlockDefinition> parseBlocksFromArray(String response) {
        try {
            return BlockJsonReader.parseAnswer(response, objectMapper);
        } catch (Exception e) {
            log.warn("Failed to parse blocks from auto-fix response: {}", e.getMessage());
            return List.of();
        }
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
