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

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class RetryBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String command = context.resolveTemplate((String) config.get("command"));
        int maxRetries = ((Number) config.getOrDefault("max_retries", 3)).intValue();
        int delaySeconds = ((Number) config.getOrDefault("delay", 5)).intValue();
        boolean exponentialBackoff = Boolean.TRUE.equals(config.get("exponential_backoff"));

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

            lastResult = commandExecutor.executeStep(
                    context.getContainerId(),
                    context.getCliType(),
                    BlockDefinition.builder()
                            .type("simple")
                            .config(Map.of("command", command))
                            .build(),
                    context.nextStepIndex()
            );

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
