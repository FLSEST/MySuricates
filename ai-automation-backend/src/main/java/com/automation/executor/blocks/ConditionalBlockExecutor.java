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
public class ConditionalBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String condition = context.resolveTemplate((String) config.get("condition"));
        String thenCommand = (String) config.get("then_command");
        String elseCommand = (String) config.get("else_command");
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
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
            String conditionResult = commandExecutor.executeOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(context.getProjectPath())
                            .command("Evaluate this condition and respond with ONLY the word TRUE or FALSE, nothing else: " + condition)
                            .model(context.modelFor(block))
                            .timeoutSeconds(claudeTimeout)
                            .providerId(context.providerFor(block))
                            .effort(context.effortFor(block))
                            .build()
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
                        HostExecutionRequest.builder()
                                .projectPath(context.getProjectPath())
                                .block(BlockDefinition.builder()
                                        .type("simple")
                                        .config(Map.of("command", resolved))
                                        .build())
                                .stepIndex(context.nextStepIndex())
                                .systemPrompt(context.getInstructionPrefix())
                                .model(context.modelFor(block))
                                .timeoutSeconds(claudeTimeout)
                                .providerId(context.providerFor(block))
                                .effort(context.effortFor(block))
                                .build()
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
