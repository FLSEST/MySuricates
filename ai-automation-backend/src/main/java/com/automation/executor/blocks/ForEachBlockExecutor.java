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
import com.automation.service.workflow.ExecutionBroadcaster;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
    public StepResult execute(BlockDefinition block, ExecutionContext context) {
        Settings settings = Settings.from(block);
        List<String> items = collectItems(block.getConfig(), settings, context);
        int totalItems = items.size();

        StepResult aggregateResult = new StepResult();
        aggregateResult.setStepIndex(context.nextStepIndex());
        aggregateResult.setStartedAt(Instant.now());
        aggregateResult.setInput("foreach " + settings.variableName() + " in [" + totalItems + " items]");

        String workflowId = null;
        if (settings.workflowId() != null) {
            StringBuilder refusal = new StringBuilder();
            workflowId = resolveSubWorkflow(settings, context, refusal);
            if (workflowId == null) return refused(aggregateResult, refusal.toString(), totalItems);
            StepAiMetadata.put(aggregateResult, "subWorkflowId", workflowId);
            // The output copies the child runs' outputs; their markers count in the child runs only (6e5d6e2c)
            StepAiMetadata.put(aggregateResult, "derivedOutput", true);
        }

        StringBuilder outputs = new StringBuilder();
        List<Map<String, Object>> recordedItems = new ArrayList<>();
        ForEachFailures failures = new ForEachFailures(
                totalItems, settings.maxConsecutiveFailures(), settings.continueOnError());
        String runId = context.getWorkflowRunId();
        int stepIndex = aggregateResult.getStepIndex();
        Set<String> itemFields = Set.of();

        for (int i = 0; i < totalItems && !context.shouldStop(); i++) {
            String item = items.get(i);
            broadcaster.broadcastLogMessage(runId, "foreach_item_started", Map.of(
                    "stepIndex", stepIndex,
                    "itemIndex", i,
                    "item", item,
                    "totalItems", totalItems
            ));
            itemFields = setItemVariables(settings, context, item, i, itemFields);

            StepResult itemResult = workflowId != null
                    ? runWorkflow(workflowId, settings.childMapping(), context)
                    : settings.itemStep().run(commandExecutor, context, settings.command());
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
            if (recordedItems.size() < MAX_RECORDED_ITEMS) recordedItems.add(recordedItem(i, item, itemResult));
            failures.record(i, item, itemResult);
            if (failures.aborted()) break;
        }

        if (failures.aborted()) log.warn("foreach in run {} {}", runId, failures.abortReason());
        failures.report(aggregateResult, outputs);
        StepAiMetadata.put(aggregateResult, "totalItems", totalItems);
        StepAiMetadata.put(aggregateResult, "items", List.copyOf(recordedItems));
        aggregateResult.setOutput(outputs.toString().trim());
        aggregateResult.setStatus(failures.failsTheBlock() ? StepStatus.FAILED : StepStatus.COMPLETED);
        return completed(aggregateResult);
    }

    /**
     * The settings of one foreach block, read once from its config.
     *
     * @param workflowId   {@code workflow_id}: one sub-workflow run per item instead of the command (TODO a5c9fbbf)
     * @param childMapping {@code context}: child variable name → template, resolved per item
     * @param itemStep     the command as each item runs it (shell or AI)
     */
    private record Settings(String variableName, String command, boolean escapeVariables, int shellTimeout,
                            boolean itemsFromCommand, boolean continueOnError, int maxConsecutiveFailures,
                            String workflowId, Map<String, Object> childMapping, InnerStep itemStep) {

        @SuppressWarnings("unchecked")
        static Settings from(BlockDefinition block) {
            Map<String, Object> config = block.getConfig();
            String variableName = (String) config.getOrDefault("variable_name", "item");
            String command = (String) config.get("command");
            boolean isShell = ExecutionMode.isShell(config);
            boolean escapeVariables = Boolean.TRUE.equals(config.get("shell_escape_variables"));
            boolean continueOnError = Boolean.parseBoolean(String.valueOf(config.getOrDefault("continue_on_error", "false")));
            int maxConsecutiveFailures = ForEachFailures.maxConsecutiveFailures(config.get("max_consecutive_failures"));
            String workflowId = config.get("workflow_id") instanceof String id && !id.isBlank() ? id : null;
            Map<String, Object> childMapping = config.get("context") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
            int shellTimeout = ((Number) config.getOrDefault("timeout_seconds", SHELL_TIMEOUT_SECONDS)).intValue();
            Integer claudeTimeout = config.containsKey("timeout_seconds")
                    ? ((Number) config.get("timeout_seconds")).intValue()
                    : null;
            String itemsSource = (String) config.getOrDefault("items_source", "list");
            boolean itemsFromCommand = "command".equals(itemsSource);
            return new Settings(variableName, command, escapeVariables, shellTimeout, itemsFromCommand,
                    continueOnError, maxConsecutiveFailures, workflowId, childMapping,
                    new InnerStep(block, isShell, escapeVariables, shellTimeout, claudeTimeout));
        }
    }

    /** The items: the output lines of {@code items_command}, or the static {@code items}. */
    private List<String> collectItems(Map<String, Object> config, Settings settings, ExecutionContext context) {
        if (!settings.itemsFromCommand()) return ForEachItems.staticItems(config.get("items"));
        String itemsCommand = context.resolveShellTemplate(
                (String) config.get("items_command"), settings.escapeVariables());
        // items_command always runs as shell — it collects iteration targets, not AI work
        String output = commandExecutor.executeShellCommandOnHost(
                context.getProjectPath(), itemsCommand, settings.shellTimeout()).output();
        return ForEachItems.splitIntoItems(output);
    }

    /**
     * The workflow each item runs, or {@code null} with the reason in {@code refusal}: a command excludes
     * {@code workflow_id}, a {@code context} entry must not name a reserved variable, and the id has to name
     * a workflow the run may start.
     */
    private String resolveSubWorkflow(Settings settings, ExecutionContext context, StringBuilder refusal) {
        Set<String> platformNames = subWorkflows.platformVariableNames();
        String reserved = settings.childMapping().keySet().stream()
                .map(name -> SubWorkflowLauncher.reservedReason(name, settings.variableName(), platformNames))
                .filter(Objects::nonNull).findFirst().orElse(null);
        String command = settings.command();
        if (command != null && !command.isBlank()) refusal.append("command and workflow_id exclude each other");
        else if (reserved != null) refusal.append(reserved);
        else return SubWorkflowLauncher.resolveWorkflowId(settings.workflowId(), context, refusal);
        return null;
    }

    /**
     * Sets the item, its index and — for a JSON object item — its fields as variables; returns the field
     * names, which the next item clears unless it has them too. Items collected by {@code items_command}
     * inherit the run's taint; static items are trusted.
     */
    private static Set<String> setItemVariables(Settings settings, ExecutionContext context, String item, int index,
                                                Set<String> previousFields) {
        String variableName = settings.variableName();
        Map<String, Object> itemVariables = new LinkedHashMap<>();
        itemVariables.put(variableName, item);
        itemVariables.put(variableName + "_index", index);
        Map<String, String> fields = ForEachItems.fields(variableName, item);
        itemVariables.putAll(fields);
        // The fields of the previous item must not stand in for missing ones of this item
        previousFields.stream().filter(name -> !fields.containsKey(name)).forEach(context.getVariables()::remove);
        itemVariables.forEach((name, value) -> {
            if (settings.itemsFromCommand()) context.setDerivedVariable(name, value);
            else context.setVariable(name, value);
        });
        return fields.keySet();
    }

    /** One item in the step metadata's {@code items} list. */
    private static Map<String, Object> recordedItem(int index, String item, StepResult itemResult) {
        Map<String, Object> recorded = new LinkedHashMap<>(Map.of(
                "index", index,
                "item", truncate(item, ForEachFailures.ITEM_LABEL_MAX_CHARS),
                "status", itemResult.getStatus().name(),
                "durationMs", itemResult.getDurationMs() != null ? itemResult.getDurationMs() : 0L));
        if (!subRunId(itemResult).isEmpty()) recorded.put("subRunId", subRunId(itemResult));
        return recorded;
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
        return completed(aggregateResult);
    }

    private static StepResult completed(StepResult aggregateResult) {
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
