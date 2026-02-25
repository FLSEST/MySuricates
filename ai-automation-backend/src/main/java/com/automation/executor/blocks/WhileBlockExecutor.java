package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WhileBlockExecutor implements BlockExecutor {

    private static final int MAX_ITERATIONS = 100;

    private final CommandExecutorService commandExecutor;

    private static final int DEFAULT_SHELL_TIMEOUT = 300;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String command = (String) config.get("command");
        String conditionCommand = (String) config.get("condition_command");
        int maxIterations = ((Number) config.getOrDefault("max_iterations", MAX_ITERATIONS)).intValue();
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", DEFAULT_SHELL_TIMEOUT)).intValue();
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(Instant.now());
        aggregateResult.setInput("while loop (max " + maxIterations + " iterations)");

        StringBuilder outputs = new StringBuilder();
        int iteration = 0;

        while (iteration < maxIterations && !context.shouldStop()) {
            // Check condition
            if (conditionCommand != null) {
                String resolvedCondition = context.resolveTemplate(conditionCommand);
                if (isShell) {
                    // In shell mode, use exit code: 0 = true (continue), non-zero = false (stop)
                    int exitCode = commandExecutor.executeShellCommandOnHost(
                            context.getProjectPath(), resolvedCondition, shellTimeout).exitCode();
                    if (exitCode != 0) {
                        break;
                    }
                } else {
                    String conditionResult = commandExecutor.executeCommandOnHost(
                            context.getProjectPath(), resolvedCondition, null, null, claudeTimeout).output();
                    if ("false".equalsIgnoreCase(conditionResult.trim()) ||
                            "0".equals(conditionResult.trim()) ||
                            conditionResult.trim().isEmpty()) {
                        break;
                    }
                }
            }

            String resolvedCommand = context.resolveTemplate(command);
            StepResult stepResult;
            if (isShell) {
                stepResult = commandExecutor.executeStepAsShell(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("shell")
                                .config(Map.of("command", resolvedCommand))
                                .build(),
                        context.nextStepIndex(),
                        shellTimeout
                );
            } else {
                stepResult = commandExecutor.executeStepOnHost(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("simple")
                                .config(Map.of("command", resolvedCommand))
                                .build(),
                        context.nextStepIndex(),
                        context.getInstructionPrefix(),
                        context.getModel(),
                        claudeTimeout
                );
            }

            outputs.append("[iteration ").append(iteration + 1).append("]: ")
                    .append(stepResult.getOutput()).append("\n");

            context.setPreviousOutput(stepResult.getOutput());

            if (stepResult.getStatus() == StepStatus.FAILED) {
                aggregateResult.setStatus(StepStatus.FAILED);
                break;
            }

            iteration++;
        }

        aggregateResult.setOutput(outputs.toString().trim());
        if (aggregateResult.getStatus() == null) {
            aggregateResult.setStatus(StepStatus.COMPLETED);
        }
        aggregateResult.setCompletedAt(Instant.now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli()
        );

        return aggregateResult;
    }

    @Override
    public boolean supports(String blockType) {
        return "while".equals(blockType);
    }
}
