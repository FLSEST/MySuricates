package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SimpleBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Object rawCommand = block.getConfig().get("command");
        if (rawCommand == null) {
            throw new WorkflowExecutionException("Simple block requires a 'command' in config");
        }

        String command = context.resolveTemplate(rawCommand.toString());

        BlockDefinition resolved = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", command)))
                .build();

        return commandExecutor.executeStepOnHost(
                context.getProjectPath(),
                resolved,
                context.nextStepIndex(),
                context.getInstructionPrefix(),
                context.getModel()
        );
    }

    @Override
    public boolean supports(String blockType) {
        return "simple".equals(blockType);
    }
}
