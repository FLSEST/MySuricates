package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.StepAiMetadata;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;

@Component
@RequiredArgsConstructor
@Slf4j
public class SwitchBlockExecutor implements BlockExecutor {

    private static final Pattern DOLLAR_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)}");

    private final CommandExecutorService commandExecutor;

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String expression = (String) config.get("expression");
        List<Map<String, Object>> cases = (List<Map<String, Object>>) config.get("cases");
        Object defaultBlocks = config.get("default");

        StepResult result = new StepResult();
        result.setStepIndex(context.nextStepIndex());
        result.setStartedAt(Instant.now());

        // Resolve expression: support both ${varName} and {{ varName }} syntax
        String resolvedExpression = resolveExpression(expression, context);
        result.setInput("switch (" + resolvedExpression + ")");

        log.info("Switch expression resolved to: '{}'", resolvedExpression);

        // Find matching case
        String matchedCase = null;
        List<Map<String, Object>> matchedBlocks = null;

        if (cases != null) {
            for (Map<String, Object> caseEntry : cases) {
                String caseValue = (String) caseEntry.get("value");
                if (matchesValue(resolvedExpression, caseValue)) {
                    matchedCase = caseValue;
                    matchedBlocks = (List<Map<String, Object>>) caseEntry.get("blocks");
                    break;
                }
            }
        }

        // Use default if no case matched
        if (matchedBlocks == null && defaultBlocks instanceof List<?> defaultList) {
            matchedCase = "default";
            matchedBlocks = (List<Map<String, Object>>) defaultList;
        }

        if (matchedBlocks == null || matchedBlocks.isEmpty()) {
            log.info("Switch: no matching case for '{}'", resolvedExpression);
            result.setOutput("No matching case for: " + resolvedExpression);
            result.setStatus(StepStatus.COMPLETED);
            StepAiMetadata.put(result, "expression", resolvedExpression);
            StepAiMetadata.put(result, "matchedCase", "none");
            result.setCompletedAt(Instant.now());
            result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());
            return result;
        }

        log.info("Switch: matched case '{}' for expression '{}'", matchedCase, resolvedExpression);

        // Execute matched blocks
        StringBuilder outputs = new StringBuilder();
        boolean anyFailed = false;

        for (Map<String, Object> blockMap : matchedBlocks) {
            if (context.shouldStop()) break;

            BlockDefinition nestedBlock = deserializeBlock(blockMap);
            StepResult blockResult = executeNestedBlock(nestedBlock, context);
            // First nested AI block wins: provider/model of the case that ran
            StepAiMetadata.carryAi(result, blockResult);

            if (blockResult.getOutput() != null) {
                if (!outputs.isEmpty()) outputs.append("\n");
                outputs.append(blockResult.getOutput());
            }
            if (blockResult.getStatus() == StepStatus.FAILED) {
                anyFailed = true;
                if (nestedBlock.shouldStopOnFailure()) break;
            }
        }

        result.setOutput(outputs.toString().trim());
        result.setStatus(anyFailed ? StepStatus.FAILED : StepStatus.COMPLETED);
        StepAiMetadata.put(result, "expression", resolvedExpression);
        StepAiMetadata.put(result, "matchedCase", matchedCase);
        result.setCompletedAt(Instant.now());
        result.setDurationMs(result.getCompletedAt().toEpochMilli() - result.getStartedAt().toEpochMilli());

        return result;
    }

    @Override
    public boolean supports(String blockType) {
        return "switch".equals(blockType);
    }

    /**
     * Resolves the expression by replacing ${varName} with context variables,
     * then delegating to context.resolveTemplate for {{ varName }} syntax.
     */
    String resolveExpression(String expression, ExecutionContext context) {
        if (expression == null) return "";

        // First resolve ${varName} syntax
        String resolved = expression;
        Matcher matcher = DOLLAR_VAR_PATTERN.matcher(resolved);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String varName = matcher.group(1);
            Object value = context.getVariable(varName);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(value != null ? String.valueOf(value) : ""));
        }
        matcher.appendTail(sb);
        resolved = sb.toString();

        // Then resolve {{ varName }} syntax
        return context.resolveTemplate(resolved);
    }

    /**
     * Checks if the expression matches the case value.
     * Supports pipe-separated alternatives (e.g. "error|critical").
     */
    boolean matchesValue(String expression, String caseValue) {
        if (caseValue == null) return false;
        String[] alternatives = caseValue.split("\\|");
        for (String alt : alternatives) {
            if (alt.trim().equals(expression)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Deserializes a config map into a BlockDefinition.
     */
    @SuppressWarnings("unchecked")
    BlockDefinition deserializeBlock(Map<String, Object> blockMap) {
        String type = (String) blockMap.get("type");
        Map<String, Object> blockConfig = blockMap.containsKey("config")
                ? new HashMap<>((Map<String, Object>) blockMap.get("config"))
                : new HashMap<>();
        return BlockDefinition.builder()
                .type(type)
                .config(blockConfig)
                .build();
    }

    /**
     * Executes a nested block using CommandExecutorService, matching the pattern
     * from ConditionalBlockExecutor.
     */
    private StepResult executeNestedBlock(BlockDefinition nestedBlock, ExecutionContext context) {
        String type = nestedBlock.getType();
        Map<String, Object> nestedConfig = nestedBlock.getConfig();
        String executionMode = (String) nestedConfig.getOrDefault("execution_mode", "claude");
        boolean isShell = "shell".equals(executionMode) || "shell".equals(type);
        int shellTimeout = ((Number) nestedConfig.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
        Integer claudeTimeout = nestedConfig.containsKey("timeout_seconds")
                ? ((Number) nestedConfig.get("timeout_seconds")).intValue()
                : null;

        // Shell-Bloecke: Taint-Escaping wie im ShellBlockExecutor (untrusted immer, alles bei Option)
        boolean escapeVariables = Boolean.TRUE.equals(nestedConfig.get("shell_escape_variables"));
        String command = isShell
                ? context.resolveShellTemplate((String) nestedConfig.get("command"), escapeVariables)
                : context.resolveTemplate((String) nestedConfig.get("command"));

        if (isShell) {
            return commandExecutor.executeStepAsShell(
                    context.getProjectPath(),
                    CommandExecutorService.buildResolvedShellBlock(nestedConfig, command, context),
                    context.nextStepIndex(),
                    shellTimeout
            );
        } else {
            return commandExecutor.executeStepOnHost(
                    HostExecutionRequest.builder()
                            .projectPath(context.getProjectPath())
                            .block(BlockDefinition.builder()
                                    .type("simple")
                                    .config(Map.of("command", command))
                                    .build())
                            .stepIndex(context.nextStepIndex())
                            .systemPrompt(context.getInstructionPrefix())
                            .model(context.modelFor(nestedBlock))
                            .timeoutSeconds(claudeTimeout)
                            .providerId(context.providerFor(nestedBlock))
                            .effort(context.effortFor(nestedBlock))
                            .build()
            );
        }
    }
}
