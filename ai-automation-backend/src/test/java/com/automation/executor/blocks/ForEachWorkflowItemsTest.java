package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.StepFailureSummary;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.ExecutionBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** foreach with {@code workflow_id}: one sub-workflow run per item (TODO a5c9fbbf). */
@ExtendWith(MockitoExtension.class)
class ForEachWorkflowItemsTest {

    @Mock private CommandExecutorService commandExecutor;
    @Mock private ExecutionBroadcaster broadcaster;
    @Mock private SubWorkflowLauncher launcher;

    private ForEachBlockExecutor executor;
    private ExecutionContext context;
    /** Variables the parent context held at each child start — the context mutates afterwards. */
    private final List<Map<String, Object>> seenByChild = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executor = new ForEachBlockExecutor(commandExecutor, broadcaster, launcher);
        context = ExecutionContext.builder().workflowRunId("run-1").build();
        context.setProjectPath("/test/project");
    }

    private void childRuns(boolean... success) {
        int[] call = {0};
        when(launcher.run(anyString(), any(ExecutionContext.class))).thenAnswer(inv -> {
            ExecutionContext ctx = inv.getArgument(1);
            seenByChild.add(new HashMap<>(ctx.getVariables()));
            int n = call[0]++;
            boolean ok = success[Math.min(n, success.length - 1)];
            return new SubWorkflowLauncher.Outcome(inv.getArgument(0), "Kind", "sub-run-" + n,
                    ok ? RunStatus.COMPLETED : RunStatus.FAILED, ok ? "done " + n : "broken " + n, ok);
        });
    }

    private static BlockDefinition foreach(Object items, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("items", items);
        config.put("variable_name", "feature");
        config.put("workflow_id", "wf-child");
        config.putAll(extra);
        return BlockDefinition.builder().type("foreach").config(config).build();
    }

    @Test
    void runsOneChildPerItem_withItemIndexAndJsonFields_andNoCommand() {
        childRuns(true);

        StepResult result = executor.execute(foreach(List.of("{\"id\": \"auth\", \"desc\": \"Login\"}", "plain"),
                Map.of("context", Map.of("feature_id", "{{ feature.id }}"))), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(launcher, times(2)).run(eq("wf-child"), any(ExecutionContext.class));
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(seenByChild.get(0))
                .containsEntry("feature", "{\"id\": \"auth\", \"desc\": \"Login\"}")
                .containsEntry("feature_index", 0)
                .containsEntry("feature.id", "auth")
                .containsEntry("feature.desc", "Login")
                .containsEntry("feature_id", "auth");
        assertThat(seenByChild.get(1)).containsEntry("feature", "plain").containsEntry("feature_index", 1)
                .doesNotContainKey("feature.id")
                .as("an unresolvable mapping stays literal, as everywhere else")
                .containsEntry("feature_id", "{{ feature.id }}");
        assertThat(result.getOutput()).contains("[plain]: done 1");
    }

    @Test
    @SuppressWarnings("unchecked")
    void recordsTheChildRunPerItem_andMarksTheOutputAsCopied() {
        childRuns(true, false);

        StepResult result = executor.execute(foreach(List.of("a", "b"), Map.of("continue_on_error", true)), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("derivedOutput", true).containsEntry("subWorkflowId", "wf-child");
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).extracting(m -> m.get("subRunId")).containsExactly("sub-run-0", "sub-run-1");
        assertThat(items).extracting(m -> m.get("status")).containsExactly("COMPLETED", "FAILED");
        ArgumentCaptor<Map<String, Object>> live = ArgumentCaptor.forClass(Map.class);
        verify(broadcaster, times(2)).broadcastLogMessage(eq("run-1"), eq("foreach_item_completed"), live.capture());
        assertThat(live.getAllValues()).extracting(m -> m.get("subRunId")).containsExactly("sub-run-0", "sub-run-1");
    }

    @Test
    void failedChildren_countTowardsMaxConsecutiveFailures() {
        childRuns(false);

        StepResult result = executor.execute(foreach(List.of("a", "b", "c", "d", "e"), Map.of()), context);

        verify(launcher, times(3)).run(anyString(), any(ExecutionContext.class));
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).containsEntry("skippedItemCount", 2);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items")
                .contains("[a]: broken 0");
    }

    @Test
    void workflowIdFromATrustedVariable_isResolved() {
        context.setVariable("feature_workflow_id", "wf-from-context");
        childRuns(true);

        executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ feature_workflow_id }}")), context);

        verify(launcher).run(eq("wf-from-context"), any(ExecutionContext.class));
    }

    @Test
    void workflowIdFromAnUntrustedVariable_isRefusedBeforeTheFirstItem() {
        context.setUntrustedVariable("target", "wf-any");

        StepResult result = executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ target }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("trusted context variables").contains("{{ target }}");
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }

    @Test
    void workflowIdThatDoesNotResolve_failsTheBlockWithTheReason() {
        StepResult result = executor.execute(foreach(List.of("a"), Map.of("workflow_id", "{{ missing }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY)).contains("does not resolve");
        assertThat(result.getMetadata()).containsEntry("totalItems", 1);
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }

    @Test
    void itemsFromATaintedCommand_reachTheChildAsUntrusted() {
        context.setUntrustedVariable("branch", "main");
        when(commandExecutor.executeShellCommandOnHost(any(), any(), org.mockito.ArgumentMatchers.anyInt()))
                .thenReturn(new com.automation.service.cli.CliSession.CommandResult("{\"id\": \"x; rm\"}\n", 0));
        childRuns(true);
        Map<String, Object> config = new HashMap<>(Map.of("items_source", "command", "items_command", "list",
                "variable_name", "feature", "workflow_id", "wf-child",
                "context", Map.of("feature_id", "{{ feature.id }}")));

        executor.execute(BlockDefinition.builder().type("foreach").config(config).build(), context);

        assertThat(context.isUntrusted("feature")).isTrue();
        assertThat(context.isUntrusted("feature.id")).isTrue();
        assertThat(context.isUntrusted("feature_id")).isTrue();
        assertThat(seenByChild.get(0)).containsEntry("feature_id", "x; rm");
    }

    @Test
    void jsonFieldsWorkInTheCommandToo() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("ok").build());
        Map<String, Object> config = new HashMap<>(Map.of("items", List.of("{\"id\": 7, \"tags\": [\"a\"]}"),
                "command", "Baue {{ item.id }} mit {{ item.tags }} (#{{ item_index }})"));

        executor.execute(BlockDefinition.builder().type("foreach").config(config).build(), context);

        ArgumentCaptor<HostExecutionRequest> request = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(request.capture());
        assertThat(request.getValue().block().getConfig().get("command")).isEqualTo("Baue 7 mit [\"a\"] (#0)");
    }

    @Test
    void commandAndWorkflowIdTogether_failTheBlock() {
        StepResult result = executor.execute(foreach(List.of("a"), Map.of("command", "echo {{ feature }}")), context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).isEqualTo("command and workflow_id exclude each other");
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    /** Review a5c9fbbf: `context` must not overwrite what the platform or the loop itself owns in the child. */
    @Test
    void contextMappingToAReservedName_failsTheBlockBeforeTheFirstItem() {
        when(launcher.platformVariableNames()).thenReturn(java.util.Set.of("open_todos"));
        for (String name : List.of("__parent_workflow_ids", "__untrusted_keys", "open_todos", "previous_output",
                "feature", "feature_index", "feature.id", "workflow_id", "workflow_run_id", "trigger_source")) {
            StepResult result = executor.execute(foreach(List.of("a"), Map.of("context", Map.of(name, "x"))), context);

            assertThat(result.getStatus()).as(name).isEqualTo(StepStatus.FAILED);
            assertThat(result.getOutput()).as(name).contains("context").contains(name);
        }
        verify(launcher, never()).run(anyString(), any(ExecutionContext.class));
    }
}
