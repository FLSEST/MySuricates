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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private final CommandExecutorService commandExecutor;
    private final ExecutionBroadcaster broadcaster;
    private final SubWorkflowLauncher subWorkflows;

    @Override
    @SuppressWarnings("unchecked")
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        String variableName = (String) config.getOrDefault("variable_name", "item");
        String command = (String) config.get("command");
        boolean isShell = ExecutionMode.isShell(config);
        boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
        boolean continueOnError = Boolean.parseBoolean(String.valueOf(config.getOrDefault("continue_on_error", "false")));
        int maxConsecutiveFailures = ForEachFailures.maxConsecutiveFailures(config.get("max_consecutive_failures"));
        // workflow_id: one sub-workflow run per item instead of the command (TODO a5c9fbbf)
        String configuredWorkflow = config.get("workflow_id") instanceof String id && !id.isBlank() ? id : null;
        Map<String, Object> childMapping = config.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
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
            items = ForEachItems.splitIntoItems(output);
        } else {
            Object rawItems = config.get("items");
            if (rawItems instanceof List<?> list) {
                items = list.stream().map(Object::toString).filter(item -> !item.isBlank()).toList();
            } else if (rawItems instanceof String s) {
                items = ForEachItems.splitIntoItems(s);
            } else {
                items = List.of();
            }
        }

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(Instant.now());
        aggregateResult.setInput("foreach " + variableName + " in [" + items.size() + " items]");

        String workflowId = null;
        if (configuredWorkflow != null) {
            StringBuilder refusal = new StringBuilder();
            Set<String> platformNames = subWorkflows.platformVariableNames();
            String reserved = childMapping.keySet().stream()
                    .map(name -> SubWorkflowLauncher.reservedReason(name, variableName, platformNames))
                    .filter(java.util.Objects::nonNull).findFirst().orElse(null);
            if (command != null && !command.isBlank()) refusal.append("command and workflow_id exclude each other");
            else if (reserved != null) refusal.append(reserved);
            else workflowId = SubWorkflowLauncher.resolveWorkflowId(configuredWorkflow, context, refusal);
            if (workflowId == null) return refused(aggregateResult, refusal.toString(), items.size());
            StepAiMetadata.put(aggregateResult, "subWorkflowId", workflowId);
            // The output copies the child runs' outputs; their markers count in the child runs only (6e5d6e2c)
            StepAiMetadata.put(aggregateResult, "derivedOutput", true);
        }

        StringBuilder outputs = new StringBuilder();
        boolean anyFailed = false;
        List<String> failedItems = new ArrayList<>();
        String firstFailureOutput = null;
        List<Map<String, Object>> recordedItems = new ArrayList<>();
        String runId = context.getWorkflowRunId();
        int stepIndex = aggregateResult.getStepIndex();
        int totalItems = items.size();
        int consecutiveFailures = 0;
        int skippedItems = 0;
        Set<String> itemFields = Set.of();

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
            Map<String, Object> itemVariables = new LinkedHashMap<>();
            itemVariables.put(variableName, item);
            itemVariables.put(variableName + "_index", i);
            Map<String, String> fields = ForEachItems.fields(variableName, item);
            itemVariables.putAll(fields);
            // The fields of the previous item must not stand in for missing ones of this item
            itemFields.stream().filter(name -> !fields.containsKey(name)).forEach(context.getVariables()::remove);
            itemFields = fields.keySet();
            itemVariables.forEach((name, value) -> {
                if (itemsFromCommand) context.setDerivedVariable(name, value);
                else context.setVariable(name, value);
            });

            StepResult itemResult = workflowId != null
                    ? runWorkflow(workflowId, childMapping, context)
                    : runCommand(block, command, isShell, escapeVariables, shellTimeout, claudeTimeout, context);
            boolean failed = itemResult.getStatus() == StepStatus.FAILED;
            broadcaster.broadcastLogMessage(runId, "foreach_item_completed", Map.of(
                    "stepIndex", stepIndex,
                    "itemIndex", i,
                    "item", item,
                    "totalItems", totalItems,
                    "status", itemResult.getStatus().name(),
                    "output", itemResult.getOutput() != null ? itemResult.getOutput() : "",
                    "subRunId", subRunId(itemResult)
            ));

            outputs.append("[").append(item).append("]: ").append(itemResult.getOutput()).append("\n");
            StepAiMetadata.carryAi(aggregateResult, itemResult);
            if (recordedItems.size() < MAX_RECORDED_ITEMS) {
                Map<String, Object> recorded = new LinkedHashMap<>(Map.of(
                        "index", i,
                        "item", truncate(item, ForEachFailures.ITEM_LABEL_MAX_CHARS),
                        "status", itemResult.getStatus().name(),
                        "durationMs", itemResult.getDurationMs() != null ? itemResult.getDurationMs() : 0L));
                if (!subRunId(itemResult).isEmpty()) recorded.put("subRunId", subRunId(itemResult));
                recordedItems.add(recorded);
            }
            if (failed) {
                anyFailed = true;
                failedItems.add(item);
                if (firstFailureOutput == null) {
                    firstFailureOutput = itemResult.getOutput() != null ? itemResult.getOutput() : "";
                }
                consecutiveFailures++;
            } else {
                consecutiveFailures = 0;
            }
            if (maxConsecutiveFailures > 0 && consecutiveFailures >= maxConsecutiveFailures && i < totalItems - 1) {
                skippedItems = totalItems - 1 - i;
                break;
            }
        }

        String abortReason = skippedItems > 0 ? ForEachFailures.abortReason(consecutiveFailures, skippedItems) : null;
        if (abortReason != null) {
            log.warn("foreach in run {} {}", runId, abortReason);
            StepAiMetadata.put(aggregateResult, "abortedAfterConsecutiveFailures", consecutiveFailures);
            StepAiMetadata.put(aggregateResult, "skippedItemCount", skippedItems);
            outputs.append("\n[max_consecutive_failures] ").append(abortReason);
        }

        if (anyFailed) {
            StepAiMetadata.put(aggregateResult, "failedItemCount", failedItems.size());
            StepAiMetadata.put(aggregateResult, "failedItems", List.copyOf(failedItems));
            if (continueOnError) {
                outputs.append("\n[continue_on_error] ").append(failedItems.size()).append("/").append(totalItems)
                        .append(" items failed: ").append(String.join(", ", failedItems));
            }
            if (!continueOnError || abortReason != null) {
                String summary = ForEachFailures.failureSummary(failedItems, firstFailureOutput, totalItems);
                StepFailureSummary.put(aggregateResult, abortReason != null ? abortReason + "\n" + summary : summary);
            }
        }
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.copyOf(recordedItems));

        aggregateResult.setOutput(outputs.toString().trim());
        // continue_on_error tolerates failed items, not a loop that stopped with items left unrun
        aggregateResult.setStatus((anyFailed && !continueOnError) || abortReason != null
                ? StepStatus.FAILED : StepStatus.COMPLETED);
        aggregateResult.setCompletedAt(Instant.now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli()
        );

        return aggregateResult;
    }

    /** One item as a command: a shell call or an AI call, as before {@code workflow_id} existed. */
    private StepResult runCommand(BlockDefinition block, String command, boolean isShell, boolean escapeVariables,
                                  int shellTimeout, Integer claudeTimeout, ExecutionContext context) {
        Map<String, Object> config = block.getConfig();
        if (isShell) {
            String resolvedCommand = context.resolveShellTemplate(command, escapeVariables);
            return commandExecutor.executeStepAsShell(context.getProjectPath(),
                    CommandExecutorService.buildResolvedShellBlock(config, resolvedCommand, context),
                    context.nextStepIndex(), shellTimeout);
        }
        return commandExecutor.executeStepOnHost(HostExecutionRequest.builder()
                .projectPath(context.getProjectPath())
                .block(BlockDefinition.builder().type("simple")
                        .config(Map.of("command", context.resolvePromptTemplate(command))).build())
                .stepIndex(context.nextStepIndex())
                .systemPrompt(context.getInstructionPrefix())
                .model(context.modelFor(block))
                .timeoutSeconds(claudeTimeout)
                .providerId(context.providerFor(block))
                .effort(context.effortFor(block))
                .build());
    }

    /**
     * One item as a run of workflow {@code workflowId}, through the same {@link SubWorkflowLauncher} as the
     * sub-workflow block. The child sees the item variables and, per {@code context} entry, the resolved
     * value under its own name; those derived values carry the run's taint like the item itself.
     */
    private StepResult runWorkflow(String workflowId, Map<String, Object> mapping, ExecutionContext context) {
        Instant start = Instant.now();
        mapping.forEach((name, template) ->
                context.setDerivedVariable(name, context.resolvePromptTemplate(String.valueOf(template))));
        SubWorkflowLauncher.Outcome outcome = subWorkflows.run(workflowId, context);
        Instant end = Instant.now();
        StepResult result = StepResult.builder()
                .output(outcome.output())
                .status(outcome.success() ? StepStatus.COMPLETED : StepStatus.FAILED)
                .startedAt(start).completedAt(end).durationMs(end.toEpochMilli() - start.toEpochMilli())
                .build();
        if (outcome.started()) StepAiMetadata.put(result, "subRunId", outcome.subRunId());
        return result;
    }

    private static String subRunId(StepResult itemResult) {
        return itemResult.getMetadata() != null && itemResult.getMetadata().get("subRunId") instanceof String id ? id : "";
    }

    /** A workflow_id that names no workflow the run may start: the block fails before the first item. */
    private static StepResult refused(StepResult aggregateResult, String reason, int totalItems) {
        aggregateResult.setOutput(reason);
        StepFailureSummary.put(aggregateResult, reason);
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.of());
        aggregateResult.setStatus(StepStatus.FAILED);
        aggregateResult.setCompletedAt(Instant.now());
        aggregateResult.setDurationMs(
                aggregateResult.getCompletedAt().toEpochMilli() - aggregateResult.getStartedAt().toEpochMilli());
        return aggregateResult;
    }

    @Override
    public Set<String> supportedTypes() {
        return Set.of("foreach");
    }
}
