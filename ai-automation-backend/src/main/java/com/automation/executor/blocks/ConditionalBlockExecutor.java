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

    private final CommandExecutorService commandExecutor;

    @Override
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String condition = context.resolveTemplate((String) config.get("condition"));
        String thenCommand = (String) config.get("then_command");
        String elseCommand = (String) config.get("else_command");

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());

        // Evaluate condition by asking Claude
        String conditionResult = commandExecutor.executeCommand(
                context.getContainerId(), "claude",
                "Evaluate this condition and respond with only 'true' or 'false': " + condition
        ).output();

        boolean conditionMet = conditionResult.trim().toLowerCase().contains("true");
        String commandToExecute = conditionMet ? thenCommand : elseCommand;

        result.setInput("if (" + condition + ") → " + conditionMet);

        if (commandToExecute != null) {
            String resolved = context.resolveTemplate(commandToExecute);
            StepResult stepResult = commandExecutor.executeStep(
                    context.getContainerId(),
                    BlockExecutor.cliTypeFor("simple"),
                    BlockDefinition.builder()
                            .type("simple")
                            .config(Map.of("command", resolved))
                            .build(),
                    context.nextStepIndex()
            );

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
