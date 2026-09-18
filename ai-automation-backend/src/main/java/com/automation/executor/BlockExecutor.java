package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;

import java.util.Set;

public interface BlockExecutor {

    StepResult execute(BlockDefinition block, ExecutionContext context);

    /**
     * The block type(s) this executor runs — the single source of truth for which types exist.
     * {@link BlockTypeRegistry} collects them from all executor beans; saving a workflow with any
     * other type is refused (TODO 89dd9e34).
     */
    Set<String> supportedTypes();

    default boolean supports(String blockType) {
        return blockType != null && supportedTypes().contains(blockType);
    }

    /**
     * Determines the CLI type for a given block type.
     * All blocks run through Claude by default.
     */
    static String cliTypeFor(String blockType) {
        return "claude";
    }
}
