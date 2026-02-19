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

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String command = (String) config.get("command");
        String conditionCommand = (String) config.get("condition_command");
        int maxIterations = ((Number) config.getOrDefault("max_iterations", MAX_ITERATIONS)).intValue();

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(Instant.now());
        aggregateResult.setInput("while loop (max " + maxIterations + " iterations)");

        StringBuilder outputs = new StringBuilder();
        int iteration = 0;

        while (iteration < maxIterations && !context.shouldStop()) {
            // Check condition
            if (conditionCommand != null) {
                String conditionResult = commandExecutor.executeCommand(
                        context.getContainerId(), BlockExecutor.cliTypeFor("while"),
                        context.resolveTemplate(conditionCommand)
                ).output();

                if ("false".equalsIgnoreCase(conditionResult.trim()) ||
                        "0".equals(conditionResult.trim()) ||
                        conditionResult.trim().isEmpty()) {
                    break;
                }
            }

            String resolvedCommand = context.resolveTemplate(command);
            StepResult stepResult = commandExecutor.executeStep(
                    context.getContainerId(),
                    BlockExecutor.cliTypeFor("simple"),
                    BlockDefinition.builder()
                            .type("simple")
                            .config(Map.of("command", resolvedCommand))
                            .build(),
                    context.nextStepIndex(),
                    context.getInstructionPrefix(),
                    context.getModel()
            );

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
