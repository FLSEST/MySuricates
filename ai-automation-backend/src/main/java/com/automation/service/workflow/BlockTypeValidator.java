package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.executor.BlockTypeRegistry;
import com.automation.executor.NestedBlocks;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Refuses block types no executor can run, at save time (TODO 89dd9e34).
 *
 * <p>Called by {@link WorkflowMutationGateway}, the one place every definition write passes, so
 * UI, API, import, pool, seeder and factory are covered alike. Without it a typo like
 * {@code "shel"} was stored happily and only failed mid-run — after the earlier blocks had
 * already done their work.
 *
 * <p>Nested blocks are checked too: switch {@code cases[].blocks} and {@code default}, plus a
 * plain {@code blocks} list, recursively — walked by {@link NestedBlocks}, the same walker
 * {@code ExecutionMode} uses, so both validations cover the same containers (d15fb3a3).
 * A missing or blank type counts as unknown: it is just as
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
                check(block.getName(), block.getType());
                NestedBlocks.visit(block.getConfig(), nested -> check(nested.name(), nested.type()));
            }
        }
    }

    private void check(String name, String type) {
        boolean missing = type == null || type.isBlank();
        if (missing || !registry.isKnown(type)) {
            String label = name != null && !name.isBlank() ? name : (missing ? "(unnamed)" : type);
            throw new IllegalArgumentException("Block '" + label + "' "
                    + (missing ? "has no type" : "has unknown type '" + type + "'")
                    + " — allowed types: " + registry.knownTypesAsText());
        }
    }
}
