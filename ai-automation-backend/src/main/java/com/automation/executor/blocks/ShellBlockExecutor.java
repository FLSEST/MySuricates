package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ShellCommandService;
import com.automation.service.cli.ShellCommandService.ShellResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ShellBlockExecutor implements BlockExecutor {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;

    private final ShellCommandService shellService;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        Object rawCommand = config.get("command");
        if (rawCommand == null) {
            throw new WorkflowExecutionException("Shell block requires a 'command' in config");
        }

        String command = context.resolveTemplate(rawCommand.toString());
        int timeout = ((Number) config.getOrDefault("timeout_seconds", DEFAULT_TIMEOUT_SECONDS)).intValue();

        StepResult step = new StepResult();
        step.setStepIndex(context.nextStepIndex());
        step.setStartedAt(Instant.now());
        step.setInput(command);
        step.setStatus(StepStatus.RUNNING);

        ShellResult result = shellService.execute(command, context.getProjectPath(), timeout);

        step.setOutput(result.output());
        step.setStatus(result.exitCode() == 0 ? StepStatus.COMPLETED : StepStatus.FAILED);
        step.setCompletedAt(Instant.now());
        step.setDurationMs(result.durationMs());
        step.setMetadata(Map.of(
                "exitCode", result.exitCode(),
                "executionMode", "shell"
        ));

        return step;
    }

    @Override
    public boolean supports(String blockType) {
        return "shell".equals(blockType);
    }
}
