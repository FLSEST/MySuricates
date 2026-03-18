package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;

@Component
@RequiredArgsConstructor
@Slf4j
public class WhileBlockExecutor implements BlockExecutor {

    private static final int MAX_ITERATIONS = 100;

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String command = (String) config.get("command");
        String conditionCommand = (String) config.get("condition_command");
        int maxIterations = ((Number) config.getOrDefault("max_iterations", MAX_ITERATIONS)).intValue();
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
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
                    String conditionResult = commandExecutor.executeOnHost(
                            HostExecutionRequest.builder()
                                    .projectPath(context.getProjectPath())
                                    .command(resolvedCondition)
                                    .timeoutSeconds(claudeTimeout)
                                    .providerId(context.getProvider())
                                    .build()
                    ).output();
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
                        HostExecutionRequest.builder()
                                .projectPath(context.getProjectPath())
                                .block(BlockDefinition.builder()
                                        .type("simple")
                                        .config(Map.of("command", resolvedCommand))
                                        .build())
                                .stepIndex(context.nextStepIndex())
                                .systemPrompt(context.getInstructionPrefix())
                                .model(context.getModel())
                                .timeoutSeconds(claudeTimeout)
                                .providerId(context.getProvider())
                                .build()
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
