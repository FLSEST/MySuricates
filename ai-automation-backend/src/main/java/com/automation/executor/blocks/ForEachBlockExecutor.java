package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.BlockExecutor;
import com.automation.executor.ExecutionContext;
import com.automation.executor.ExecutionMode;
import com.automation.executor.StepAiMetadata;
import com.automation.executor.StepFailureSummary;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.ExecutionBroadcaster;
import com.automation.util.TruncationUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.automation.config.ExecutionDefaults.SHELL_TIMEOUT_SECONDS;
import static com.automation.executor.blocks.AgenticPromptBuilder.truncate;

@Component
@RequiredArgsConstructor
@Slf4j
public class ForEachBlockExecutor implements BlockExecutor {

    /** Upper bound for the per-item summary persisted in the step metadata (keeps the row small). */
    static final int MAX_RECORDED_ITEMS = 500;
    private static final int ITEM_LABEL_MAX_CHARS = 120;
    /** Budget of the failure summary: failed item names, then the error of the first one (head + tail). */
    private static final int FAILED_LABEL_MAX_CHARS = 60;
    private static final int FAILED_NAMES_MAX_CHARS = 160;
    private static final int ERROR_HEAD_CHARS = 80;
    private static final int ERROR_TAIL_CHARS = 240;

    private final CommandExecutorService commandExecutor;
    private final ExecutionBroadcaster broadcaster;

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String variableName = (String) config.getOrDefault("variable_name", "item");
        String command = (String) config.get("command");
        boolean isShell = ExecutionMode.isShell(config);
        boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
        boolean continueOnError = Boolean.parseBoolean(String.valueOf(config.getOrDefault("continue_on_error", "false")));
        int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
        Integer claudeTimeout = config.containsKey("timeout_seconds")
                ? ((Number) config.get("timeout_seconds")).intValue()
                : null;

        // Get items from list or command output
        List<String> items;
        String itemsSource = (String) config.getOrDefault("items_source", "list");
        boolean itemsFromCommand = "command".equals(itemsSource);

        if (itemsFromCommand) {
            String itemsCommand = context.resolveShellTemplate((String) config.get("items_command"), escapeVariables);
            // items_command always runs as shell — it collects iteration targets, not AI work
            String output = commandExecutor.executeShellCommandOnHost(
                    context.getProjectPath(), itemsCommand, shellTimeout).output();
            items = splitIntoItems(output);
        } else {
            Object rawItems = config.get("items");
            if (rawItems instanceof List<?> list) {
                items = list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
            } else if (rawItems instanceof String s) {
                items = splitIntoItems(s);
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
        List<String> failedItems = new ArrayList<>();
        String firstFailureOutput = null;
        List<Map<String, Object>> recordedItems = new ArrayList<>();
        String runId = context.getWorkflowRunId();
        int stepIndex = aggregateResult.getStepIndex();
        int totalItems = items.size();

        for (int i = 0; i < items.size(); i++) {
            String item = items.get(i);
            if (context.shouldStop()) break;

            broadcaster.broadcastLogMessage(runId, "foreach_item_started", Map.of(
                    "stepIndex", stepIndex,
                    "itemIndex", i,
                    "item", item,
                    "totalItems", totalItems
            ));

            // Items collected by items_command inherit the run's taint; static items are trusted
            if (itemsFromCommand) context.setDerivedVariable(variableName, item);
            else context.setVariable(variableName, item);
            String resolvedCommand = isShell
                    ? context.resolveShellTemplate(command, escapeVariables)
                    : context.resolvePromptTemplate(command);

            StepResult itemResult;
            if (isShell) {
                itemResult = commandExecutor.executeStepAsShell(
                        context.getProjectPath(),
                        CommandExecutorService.buildResolvedShellBlock(config, resolvedCommand, context),
                        context.nextStepIndex(),
                        shellTimeout
                );
            } else {
                itemResult = commandExecutor.executeStepOnHost(
                        HostExecutionRequest.builder()
                                .projectPath(context.getProjectPath())
                                .block(BlockDefinition.builder()
                                        .type("simple")
                                        .config(Map.of("command", resolvedCommand))
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

            boolean failed = itemResult.getStatus() == StepStatus.FAILED;
            broadcaster.broadcastLogMessage(runId, "foreach_item_completed", Map.of(
                    "stepIndex", stepIndex,
                    "itemIndex", i,
                    "item", item,
                    "totalItems", totalItems,
                    "status", itemResult.getStatus().name(),
                    "output", itemResult.getOutput() != null ? itemResult.getOutput() : ""
            ));

            outputs.append("[").append(item).append("]: ").append(itemResult.getOutput()).append("\n");
            StepAiMetadata.carryAi(aggregateResult, itemResult);
            if (recordedItems.size() < MAX_RECORDED_ITEMS) {
                recordedItems.add(Map.of(
                        "index", i,
                        "item", truncate(item, ITEM_LABEL_MAX_CHARS),
                        "status", itemResult.getStatus().name(),
                        "durationMs", itemResult.getDurationMs() != null ? itemResult.getDurationMs() : 0L
                ));
            }
            if (failed) {
                anyFailed = true;
                failedItems.add(item);
                if (firstFailureOutput == null) {
                    firstFailureOutput = itemResult.getOutput() != null ? itemResult.getOutput() : "";
                }
            }
        }

        if (anyFailed) {
            StepAiMetadata.put(aggregateResult, "failedItemCount", failedItems.size());
            StepAiMetadata.put(aggregateResult, "failedItems", List.copyOf(failedItems));
            if (continueOnError) {
                outputs.append("\n[continue_on_error] ").append(failedItems.size()).append("/").append(totalItems)
                        .append(" items failed: ").append(String.join(", ", failedItems));
            } else {
                StepFailureSummary.put(aggregateResult, failureSummary(failedItems, firstFailureOutput, totalItems));
            }
        }
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.copyOf(recordedItems));

        aggregateResult.setOutput(outputs.toString().trim());
        aggregateResult.setStatus(anyFailed && !continueOnError ? StepStatus.FAILED : StepStatus.COMPLETED);
        aggregateResult.setCompletedAt(Instant.now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli()
        );

        return aggregateResult;
    }

    /**
     * Where the block failed, for the run error (TODO 4a8cabc4): the failed items first, then the
     * first one with its error — {@code "2/5 items failed: a, b\n[a]: <error>"}. The output cannot
     * say that: it lists every item in order, so a failed item sits between successful ones.
     */
    static String failureSummary(List<String> failedItems, String firstFailureOutput, int totalItems) {
        StringBuilder names = new StringBuilder();
        int listed = 0;
        for (String item : failedItems) {
            String label = truncate(item, FAILED_LABEL_MAX_CHARS);
            if (listed > 0 && names.length() + label.length() > FAILED_NAMES_MAX_CHARS) break;
            names.append(listed > 0 ? ", " : "").append(label);
            listed++;
        }
        if (listed < failedItems.size()) {
            names.append(", … (+").append(failedItems.size() - listed).append(")");
        }
        String error = TruncationUtil.smart(firstFailureOutput.strip(), ERROR_HEAD_CHARS, ERROR_TAIL_CHARS);
        return failedItems.size() + "/" + totalItems + " items failed: " + names
                + "\n[" + truncate(failedItems.get(0), ITEM_LABEL_MAX_CHARS) + "]: " + error;
    }

    /**
     * Splits newline-separated item sources into items. Blank lines never become an iteration —
     * an empty command output means zero items, not one empty item (which would cost an AI call).
     */
    private static List<String> splitIntoItems(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        return Arrays.stream(raw.split("\n")).filter(line -> !line.isBlank()).toList();
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("foreach");
    }
}
