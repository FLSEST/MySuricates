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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ForEachBlockExecutor implements BlockExecutor {

    private final CommandExecutorService commandExecutor;

    private static final int DEFAULT_SHELL_TIMEOUT = 300;

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String variableName = (String) config.getOrDefault("variable_name", "item");
        String command = (String) config.get("command");
        String executionMode = (String) config.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode);
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", DEFAULT_SHELL_TIMEOUT)).intValue();

        // Get items from list or command output
        List<String> items;
        String itemsSource = (String) config.getOrDefault("items_source", "list");

        if ("command".equals(itemsSource)) {
            String itemsCommand = context.resolveTemplate((String) config.get("items_command"));
            String output;
            if (isShell) {
                output = commandExecutor.executeShellCommandOnHost(
                        context.getProjectPath(), itemsCommand, shellTimeout).output();
            } else {
                output = commandExecutor.executeCommandOnHost(
                        context.getProjectPath(), itemsCommand).output();
            }
            items = Arrays.asList(output.split("\n"));
        } else {
            Object rawItems = config.get("items");
            if (rawItems instanceof List<?> list) {
                items = list.stream().map(Object::toString).toList();
            } else if (rawItems instanceof String s) {
                items = Arrays.asList(s.split("\n"));
            } else {
                items = List.of();
            }
        }

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(Instant.now());
        aggregateResult.setInput("foreach " + variableName + " in [" + items.size() + " items]");

        StringBuilder outputs = new StringBuilder();
        boolean anyFailed = false;

        for (String item : items) {
            if (context.shouldStop()) break;

            context.setVariable(variableName, item);
            String resolvedCommand = context.resolveTemplate(command);

            StepResult itemResult;
            if (isShell) {
                itemResult = commandExecutor.executeStepAsShell(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("shell")
                                .config(Map.of("command", resolvedCommand))
                                .build(),
                        context.nextStepIndex(),
                        shellTimeout
                );
            } else {
                itemResult = commandExecutor.executeStepOnHost(
                        context.getProjectPath(),
                        BlockDefinition.builder()
                                .type("simple")
                                .config(Map.of("command", resolvedCommand))
                                .build(),
                        context.nextStepIndex(),
                        context.getInstructionPrefix(),
                        context.getModel()
                );
            }

            outputs.append("[").append(item).append("]: ").append(itemResult.getOutput()).append("\n");
            if (itemResult.getStatus() == StepStatus.FAILED) {
                anyFailed = true;
            }
        }

        aggregateResult.setOutput(outputs.toString().trim());
        aggregateResult.setStatus(anyFailed ? StepStatus.FAILED : StepStatus.COMPLETED);
        aggregateResult.setCompletedAt(Instant.now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli()
        );

        return aggregateResult;
    }

    @Override
    public boolean supports(String blockType) {
        return "foreach".equals(blockType);
    }
}
