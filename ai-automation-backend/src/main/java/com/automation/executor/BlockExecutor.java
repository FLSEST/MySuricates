package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;

public interface BlockExecutor {

    StepResult execute(BlockDefinition block, ExecutionContext context);

    boolean supports(String blockType);

    /**
     * Determines the CLI type for a given block type.
     * All blocks run through Claude by default.
     */
    static String cliTypeFor(String blockType) {
        return "claude";
    }
}
