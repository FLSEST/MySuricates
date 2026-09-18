package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.executor.BlockTypeRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * Refuses block types no executor can run, at save time (TODO 89dd9e34).
 *
 * <p>Called by {@link WorkflowMutationGateway}, the one place every definition write passes, so
 * UI, API, import, pool, seeder and factory are covered alike. Without it a typo like
 * {@code "shel"} was stored happily and only failed mid-run — after the earlier blocks had
 * already done their work.
 *
 * <p>Nested blocks are checked too: switch {@code cases[].blocks} and {@code default}, plus a
 * plain {@code blocks} list, recursively. A missing or blank type counts as unknown: it is just as
 * unexecutable, and inside a switch case it used to slip past the type check straight into the AI
 * branch (a silent {@code config.command} prompt). No live workflow carries one.
 */
@Component
@RequiredArgsConstructor
public class BlockTypeValidator {

    private final BlockTypeRegistry registry;

    /** @throws IllegalArgumentException naming the first block whose type no executor supports */
    public void rejectUnknown(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return;
        }
        for (BlockDefinition block : blocks) {
            if (block != null) {
                check(block.getName(), block.getType(), block.getConfig());
            }
        }
    }

    private void check(String name, String type, Map<String, Object> config) {
        boolean missing = type == null || type.isBlank();
        if (missing || !registry.isKnown(type)) {
            String label = name != null && !name.isBlank() ? name : (missing ? "(unnamed)" : type);
            throw new IllegalArgumentException("Block '" + label + "' "
                    + (missing ? "has no type" : "has unknown type '" + type + "'")
                    + " — allowed types: " + registry.knownTypesAsText());
        }
        checkNested(config);
    }

    private void checkNested(Map<String, Object> config) {
        if (config == null) {
            return;
        }
        if (config.get("cases") instanceof List<?> cases) {
            for (Object entry : cases) {
                if (entry instanceof Map<?, ?> caseEntry) {
                    checkNestedBlocks(caseEntry.get("blocks"));
                }
            }
        }
        checkNestedBlocks(config.get("default"));
        checkNestedBlocks(config.get("blocks"));
    }

    @SuppressWarnings("unchecked")
    private void checkNestedBlocks(Object nestedBlocks) {
        if (!(nestedBlocks instanceof List<?> list)) {
            return;
        }
        for (Object item : list) {
            if (item instanceof Map<?, ?> nested) {
                String type = nested.get("type") instanceof String s ? s : null;
                String name = nested.get("name") instanceof String s ? s : null;
                Map<String, Object> nestedConfig = nested.get("config") instanceof Map<?, ?> m
                        ? (Map<String, Object>) m
                        : null;
                check(name, type, nestedConfig);
            }
        }
    }
}
