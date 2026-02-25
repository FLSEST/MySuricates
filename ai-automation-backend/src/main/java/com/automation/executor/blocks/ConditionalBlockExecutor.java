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
public class ConditionalBlockExecutor implements BlockExecutor {

    private static final int DEFAULT_SHELL_TIMEOUT = 300;

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String condition = context.resolveTemplate((String) config.get("condition"));
        String thenCommand = (String) config.get("then_command");
        String elseCommand = (String) config.get("else_command");
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", DEFAULT_SHELL_TIMEOUT)).intValue();
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());

        boolean conditionMet;
        if (isShell) {
            // Shell mode: exit code 0 = true, non-zero = false
            int exitCode = commandExecutor.executeShellCommandOnHost(
                    context.getProjectPath(), condition, shellTimeout).exitCode();
            conditionMet = exitCode == 0;
        } else {
            // Claude mode: ask AI to evaluate condition as TRUE/FALSE
            String conditionResult = commandExecutor.executeCommandOnHost(
                    context.getProjectPath(),
                    "Evaluate this condition and respond with ONLY the word TRUE or FALSE, nothing else: " + condition,
                    null, null, claudeTimeout
            ).output();
            String trimmed = conditionResult.trim().toUpperCase();
            conditionMet = trimmed.equals("TRUE") || trimmed.startsWith("TRUE");
        }

        String commandToExecute = conditionMet ? thenCommand : elseCommand;
        result.setInput("if (" + condition + ") → " + conditionMet);

        if (commandToExecute != null) {
            String resolved = context.resolveTemplate(commandToExecute);
            StepResult stepResult;
            if (isShell) {
                stepResult = commandExecutor.executeStepAsShell(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("shell")
                                .config(Map.of("command", resolved))
                                .build(),
                        context.nextStepIndex(),
                        shellTimeout
                );
            } else {
                stepResult = commandExecutor.executeStepOnHost(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("simple")
                                .config(Map.of("command", resolved))
                                .build(),
                        context.nextStepIndex(),
                        context.getInstructionPrefix(),
                        context.getModel(),
                        claudeTimeout
                );
            }
            result.setOutput(stepResult.getOutput());
            result.setStatus(stepResult.getStatus());
        } else {
            result.setOutput("No " + (conditionMet ? "then" : "else") + " branch defined, skipping");
            result.setStatus(StepStatus.SKIPPED);
        }

        result.setCompletedAt(Instant.now());
        result.setDurationMs(
                result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli()
        );

        return result;
    }

    @Override
    public boolean supports(String blockType) {
        return "conditional".equals(blockType);
    }
}
