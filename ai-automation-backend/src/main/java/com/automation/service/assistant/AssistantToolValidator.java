package com.automation.service.assistant;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Validates assistant tool payloads BEFORE they reach the database (TODO 9f022277).
 *
 * Background: the assistant once created a workflow with structurally broken blocks
 * that crashed the entire workflows page. The confirm-gate protects against unwanted
 * actions, but not against structurally invalid payloads the user cannot judge from
 * the one-line description — that is this validator's job.
 */
@Component
@RequiredArgsConstructor
public class AssistantToolValidator {

    /** Must match the {@code supports()} strings of all BlockExecutor implementations. */
    static final Set<String> KNOWN_BLOCK_TYPES = Set.of(
            "simple", "shell", "retry", "conditional", "foreach", "while", "switch",
            "agentic", "approval", "sub-workflow", "skill-capability", "human-input",
            "integration", "document", "todo-create", "todo-update", "todo-close", "compress", "compress-ai");

    /** Block types whose config must contain a non-blank {@code command}. */
    private static final Set<String> COMMAND_BLOCK_TYPES =
            Set.of("simple", "shell", "retry", "foreach", "while");

    private final ObjectMapper objectMapper;

    /**
     * @return an error message when the payload must not be executed, empty when it is safe
     */
    public Optional<String> validate(String type, String payloadJson) {
        Map<String, Object> params;
        try {
            params = objectMapper.readValue(payloadJson, new TypeReference<>() {});
        } catch (Exception e) {
            return Optional.of("Ungültiges JSON im Tool-Payload: " + e.getMessage());
        }

        return switch (type) {
            case "create_workflow", "update_workflow" -> validateBlocks(params.get("blocks"));
            case "create_chain" -> validateChainEntries(params.get("entries"));
            default -> Optional.empty();
        };
    }

    private Optional<String> validateBlocks(Object blocksObj) {
        if (blocksObj == null) return Optional.empty();
        if (!(blocksObj instanceof List<?> blocks)) {
            return Optional.of("blocks muss eine Liste sein.");
        }
        for (int i = 0; i < blocks.size(); i++) {
            Object entry = blocks.get(i);
            if (!(entry instanceof Map<?, ?> block)) {
                return Optional.of("Block " + (i + 1) + " ist kein Objekt.");
            }
            Object typeObj = block.get("type");
            if (!(typeObj instanceof String blockType) || blockType.isBlank()) {
                return Optional.of("Block " + (i + 1) + " hat keinen type.");
            }
            if (!KNOWN_BLOCK_TYPES.contains(blockType)) {
                return Optional.of("Block " + (i + 1) + " hat unbekannten type \"" + blockType
                        + "\". Erlaubt: " + String.join(", ", KNOWN_BLOCK_TYPES.stream().sorted().toList()));
            }
            Object configObj = block.get("config");
            if (configObj != null && !(configObj instanceof Map<?, ?>)) {
                return Optional.of("Block " + (i + 1) + " (" + blockType + "): config muss ein Objekt sein.");
            }
            Map<?, ?> config = configObj instanceof Map<?, ?> m ? m : Map.of();
            if (COMMAND_BLOCK_TYPES.contains(blockType) && isBlank(config.get("command"))) {
                return Optional.of("Block " + (i + 1) + " (" + blockType + "): config.command fehlt.");
            }
            if ("agentic".equals(blockType) && isBlank(config.get("goal"))) {
                return Optional.of("Block " + (i + 1) + " (agentic): config.goal fehlt.");
            }
        }
        return Optional.empty();
    }

    private Optional<String> validateChainEntries(Object entriesObj) {
        if (!(entriesObj instanceof List<?> entries) || entries.isEmpty()) {
            return Optional.of("entries muss eine nicht-leere Liste sein.");
        }
        for (int i = 0; i < entries.size(); i++) {
            if (!(entries.get(i) instanceof Map<?, ?> entry) || isBlank(entry.get("workflowId"))) {
                return Optional.of("Chain-Eintrag " + (i + 1) + ": workflowId fehlt.");
            }
        }
        return Optional.empty();
    }

    private static boolean isBlank(Object value) {
        return !(value instanceof String s) || s.isBlank();
    }
}
