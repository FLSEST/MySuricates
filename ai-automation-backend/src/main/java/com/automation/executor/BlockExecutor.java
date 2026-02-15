package com.automation.executor;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;

public interface BlockExecutor {

    StepResult execute(BlockDefinition block, ExecutionContext context);

    boolean supports(String blockType);
}
