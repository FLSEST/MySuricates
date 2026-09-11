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

import java.util.Map;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetryBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        int maxRetries = ((Number) config.getOrDefault("max_retries", 3)).intValue();
        int delaySeconds = ((Number) config.getOrDefault("delay", 5)).intValue();
        boolean exponentialBackoff = Boolean.TRUE.equals(config.get("exponential_backoff"));
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
        String rawCommand = (String) config.get("command");
        String command = isShell
                ? context.resolveShellTemplate(rawCommand, escapeVariables)
                : context.resolveTemplate(rawCommand);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        StepResult lastResult = StepResult.builder()
                .stepIndex(context.nextStepIndex())
                .status(StepStatus.FAILED)
                .input(command)
                .output("No retry attempts executed")
                .build();

        for (int attempt = 0; attempt <= Math.max(0, maxRetries); attempt++) {
            if (context.shouldStop()) break;

            if (attempt > 0) {
                int waitTime = exponentialBackoff ? delaySeconds * (1 << (attempt - 1)) : delaySeconds;
                log.info("Retry attempt {} of {}, waiting {}s", attempt, maxRetries, waitTime);
                try {
                    Thread.sleep(waitTime * 1000L);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            if (isShell) {
                lastResult = commandExecutor.executeStepAsShell(
                        context.getProjectPath(),
                        CommandExecutorService.buildResolvedShellBlock(config, command, context),
                        context.nextStepIndex(),
                        shellTimeout
                );
            } else {
                lastResult = commandExecutor.executeStepOnHost(
                        HostExecutionRequest.builder()
                                .projectPath(context.getProjectPath())
                                .block(BlockDefinition.builder()
                                        .type("simple")
                                        .config(Map.of("command", command))
                                        .build())
                                .stepIndex(context.nextStepIndex())
                                .systemPrompt(context.getInstructionPrefix())
                                .model(context.getModel())
                                .timeoutSeconds(claudeTimeout)
                                .providerId(context.getProvider())
                                .effort(context.effortFor(block))
                                .build()
                );
            }

            if (lastResult.getStatus() == StepStatus.COMPLETED) {
                return lastResult;
            }
        }

        return lastResult;
    }

    @Override
    public boolean supports(String blockType) {
        return "retry".equals(blockType);
    }
}
