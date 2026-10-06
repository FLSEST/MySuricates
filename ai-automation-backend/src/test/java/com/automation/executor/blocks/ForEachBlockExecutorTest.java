package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.StepFailureSummary;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
import com.automation.service.workflow.ExecutionBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForEachBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @Mock
    private ExecutionBroadcaster broadcaster;

    @InjectMocks
    private ForEachBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
    }

    @Test
    void supports_foreachType() {
        assertThat(executor.supports("foreach")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_withListItems_iteratesEachItem() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("linted").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("file1.js", "file2.js", "file3.js"));
        config.put("variable_name", "file");
        config.put("command", "eslint {{ file }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_withCommandSource_usesCommandOutput() {
        when(commandExecutor.executeShellCommandOnHost("/test/project", "git diff --name-only", 300))
                .thenReturn(new CliSession.CommandResult("file1.js\nfile2.js", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "git diff --name-only");
        config.put("variable_name", "file");
        config.put("command", "eslint {{ file }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_anyItemFails_returnsFailedStatus() {
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        StepResult failed = StepResult.builder()
                .stepIndex(1).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(success, failed);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("good.js", "bad.js"));
        config.put("command", "lint {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_itemFailsBetweenPassingOnes_summaryNamesItFirst() {
        // 4a8cabc4: "Hunt Quality Rebuild" reported "failed: [oberwesel]: GATE PASS …" — the failed
        // item sat between successful ones and was cut out of the run error
        StepResult pass = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("GATE PASS").build();
        StepResult broken = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .output("API Error: Connection closed mid-response.\n").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(pass, broken, pass);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("bad-zwischenahn", "erkner", "etzenrot"));
        config.put("command", "rebuild {{ item }}");
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata())
                .containsEntry("failedItemCount", 1)
                .containsEntry("failedItems", List.of("erkner"))
                .containsEntry(StepFailureSummary.KEY,
                        "1/3 items failed: erkner\n[erkner]: API Error: Connection closed mid-response.");
        // The output stays the transcript of every item, in order
        assertThat(result.getOutput()).startsWith("[bad-zwischenahn]: GATE PASS").doesNotContain("items failed");
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void failureSummary_manyFailedItems_staysWithinBudgetAndKeepsTheErrorTail() {
        List<String> failed = IntStream.range(0, 37).mapToObj(i -> "ort-" + i).toList();
        String longError = "x".repeat(2_000) + "You've hit your session limit";

        String summary = ForEachFailures.failureSummary(failed, longError, 50);

        assertThat(summary).startsWith("37/50 items failed: ort-0, ort-1, ")
                .contains("… (+")
                .contains("\n[ort-0]: ")
                .endsWith("You've hit your session limit");
        assertThat(summary.length()).isLessThan(600);
    }

    @Test
    void execute_continueOnError_failedItemDoesNotFailBlock() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("timeout").build();
        StepResult success = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(failed, success, success);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("bad-langensalza", "gotha", "erfurt"));
        config.put("command", "rebuild {{ item }}");
        config.put("continue_on_error", true);

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        // Block completes so follow-up blocks run; failures are reported, not swallowed
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("1/3 items failed", "bad-langensalza");
        assertThat(result.getMetadata().get("failedItemCount")).isEqualTo(1);
        assertThat(result.getMetadata().get("failedItems")).isEqualTo(List.of("bad-langensalza"));
        assertThat(result.getMetadata()).as("a COMPLETED step has no run error to explain")
                .doesNotContainKey(StepFailureSummary.KEY);
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    // --- max_consecutive_failures (TODO a6659a46) ---

    private static StepResult itemResult(StepStatus status, String output) {
        return StepResult.builder().stepIndex(0).status(status).output(output).build();
    }

    private BlockDefinition foreachOver(List<String> items, Map<String, Object> extra) {
        Map<String, Object> config = new HashMap<>();
        config.put("items", items);
        config.put("command", "rebuild {{ item }}");
        config.putAll(extra);
        return BlockDefinition.builder().type("foreach").config(config).build();
    }

    @Test
    void execute_threeFailuresInARow_stopsAndNamesTheReason() {
        // 4a8cabc4 follow-up: 37 of 42 items hit "You've hit your session limit", foreach called them all
        StepResult quota = itemResult(StepStatus.FAILED, "You've hit your session limit");
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(itemResult(StepStatus.COMPLETED, "ok"), quota, quota, quota);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e", "f", "g"), Map.of()), context);

        verify(commandExecutor, times(4)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata())
                .containsEntry("abortedAfterConsecutiveFailures", 3)
                .containsEntry("skippedItemCount", 3)
                .containsEntry("failedItemCount", 3)
                .containsEntry("totalItems", 7);
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items (max_consecutive_failures), 3 items not run\n"
                        + "3/7 items failed: b, c, d\n[b]: You've hit your session limit");
        assertThat(result.getOutput()).endsWith("[max_consecutive_failures] stopped after 3 consecutive failed items"
                + " (max_consecutive_failures), 3 items not run");
    }

    @Test
    void execute_continueOnError_stillStopsAfterThreeFailuresInARow_andFails() {
        StepResult quota = itemResult(StepStatus.FAILED, "You've hit your session limit");
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(quota);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d"), Map.of("continue_on_error", true)), context);

        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getStatus()).as("continue_on_error tolerates failed items, not unrun ones")
                .isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[continue_on_error] 3/4 items failed: a, b, c",
                "[max_consecutive_failures] stopped after 3 consecutive failed items");
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY))
                .startsWith("stopped after 3 consecutive failed items (max_consecutive_failures), 1 item not run\n");
    }

    @Test
    void execute_successBetweenFailures_resetsTheCount() {
        StepResult bad = itemResult(StepStatus.FAILED, "error");
        StepResult ok = itemResult(StepStatus.COMPLETED, "ok");
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(bad, bad, ok, bad, bad, ok);

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e", "f"), Map.of("continue_on_error", true)), context);

        verify(commandExecutor, times(6)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).doesNotContainKeys("abortedAfterConsecutiveFailures", "skippedItemCount");
    }

    @Test
    void execute_lastItemCompletesTheRun_isNoAbort() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(foreachOver(List.of("a", "b", "c"), Map.of()), context);

        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getMetadata()).doesNotContainKey("abortedAfterConsecutiveFailures");
        assertThat((String) result.getMetadata().get(StepFailureSummary.KEY)).startsWith("3/3 items failed: a, b, c");
    }

    @Test
    void execute_maxConsecutiveFailuresZero_runsEveryItem() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e"), Map.of("max_consecutive_failures", 0)), context);

        verify(commandExecutor, times(5)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getMetadata()).doesNotContainKey("abortedAfterConsecutiveFailures");
    }

    @Test
    void execute_customMaxConsecutiveFailuresAsText_isHonoured() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(itemResult(StepStatus.FAILED, "error"));

        StepResult result = executor.execute(
                foreachOver(List.of("a", "b", "c", "d", "e"), Map.of("max_consecutive_failures", "1")), context);

        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
        assertThat(result.getMetadata()).containsEntry("skippedItemCount", 4);
    }

    @Test
    void maxConsecutiveFailures_readsNumbersAndText_fallsBackToDefault() {
        assertThat(ForEachFailures.maxConsecutiveFailures(null)).isEqualTo(3);
        assertThat(ForEachFailures.maxConsecutiveFailures(5)).isEqualTo(5);
        assertThat(ForEachFailures.maxConsecutiveFailures(" 2 ")).isEqualTo(2);
        assertThat(ForEachFailures.maxConsecutiveFailures(-1)).isZero();
        assertThat(ForEachFailures.maxConsecutiveFailures("{{ limit }}")).isEqualTo(3);
    }

    @Test
    void execute_continueOnError_allItemsSucceed_noFailureMetadata() {
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(success);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("a", "b"));
        config.put("command", "process {{ item }}");
        config.put("continue_on_error", true);

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).doesNotContain("items failed");
        assertThat(result.getMetadata()).doesNotContainKey("failedItemCount");
    }

    @Test
    void execute_emptyItems_returnsCompleted() {
        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of());
        config.put("command", "lint {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        assertThat(result.getMetadata().get("items")).isEqualTo(List.of());
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_itemsCommand_emptyOutput_iteratesZeroTimes() {
        // Empty items_command output = zero items, not one empty item (that would be a pointless AI call)
        when(commandExecutor.executeShellCommandOnHost("/test/project", "cat topics.txt", 300))
                .thenReturn(new CliSession.CommandResult("", 0));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "cat topics.txt");
        config.put("command", "schreibe ueber {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("items")).isEmpty();
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
        verify(commandExecutor, never()).executeStepAsShell(any(), any(), anyInt(), anyInt());
        verify(broadcaster, never()).broadcastLogMessage(eq("run-1"), eq("foreach_item_started"), any());
    }

    @Test
    void execute_itemsCommand_whitespaceOnlyOutput_iteratesZeroTimes() {
        when(commandExecutor.executeShellCommandOnHost("/test/project", "list-topics", 300))
                .thenReturn(new CliSession.CommandResult("\n  \n\n", 0));

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "list-topics");
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 0);
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_itemsCommand_blankLinesAreDropped() {
        // Blank lines in between and at the end are not items
        when(commandExecutor.executeShellCommandOnHost("/test/project", "ls", 300))
                .thenReturn(new CliSession.CommandResult("a.txt\n\n   \nb.txt\n", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "ls");
        config.put("command", "lint {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).extracting(item -> item.get("item")).containsExactly("a.txt", "b.txt");
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_stringItems_blankLinesAreDropped() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", "alpha\n\nbeta\n");
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_listItems_blankEntriesAreDropped() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "", "  ", "beta"));
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("totalItems", 2);
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_cancelledDuringIteration_stopsEarly() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenAnswer(inv -> {
                    context.cancel();
                    return stepResult;
                });

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a", "b", "c", "d"));
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_stringItems_splitsByNewline() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", "alpha\nbeta\ngamma");
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_shellMode_usesShellExecution() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a", "b"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStepAsShell(any(), any(), anyInt(), eq(300));
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_shellMode_commandSource_usesShellForItems() {
        when(commandExecutor.executeShellCommandOnHost("/test/project", "ls *.txt", 300))
                .thenReturn(new CliSession.CommandResult("a.txt\nb.txt", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "ls *.txt");
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor).executeShellCommandOnHost("/test/project", "ls *.txt", 300);
        verify(commandExecutor, times(2)).executeStepAsShell(any(), any(), anyInt(), eq(300));
    }

    @Test
    void execute_codexProvider_passesProviderIdToRequest() {
        context.setProvider("CODEX");
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.providerId() == null));
    }

    @Test
    void execute_broadcastsStartedAndCompletedEventsPerItem() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("x", "y"));
        config.put("command", "handle {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(broadcaster, times(2)).broadcastLogMessage(eq("run-1"), eq("foreach_item_started"), any());
        verify(broadcaster, times(2)).broadcastLogMessage(eq("run-1"), eq("foreach_item_completed"), any());
    }

    @Test
    void execute_outputCombinesItemResults() {
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenAnswer(inv -> {
                    HostExecutionRequest req = inv.getArgument(0);
                    String cmd = (String) req.block().getConfig().get("command");
                    return StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("processed " + cmd).build();
                });

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "beta"));
        config.put("variable_name", "item");
        config.put("command", "{{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).contains("[alpha]:");
        assertThat(result.getOutput()).contains("[beta]:");
    }

    @Test
    void execute_customTimeout_passedToShellMode() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(any(), any(), anyInt(), eq(60)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor).executeStepAsShell(any(), any(), anyInt(), eq(60));
    }

    @Test
    void execute_customClaudeTimeout_passedToHostRequest() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(argThat(req -> Integer.valueOf(120).equals(req.timeoutSeconds()))))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");
        config.put("timeout_seconds", 120);

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(argThat(req -> Integer.valueOf(120).equals(req.timeoutSeconds())));
    }

    @Test
    void execute_itemsCommand_templateVariablesResolved() {
        context.setVariable("branch", "main");
        when(commandExecutor.executeShellCommandOnHost("/test/project", "git diff main --name-only", 300))
                .thenReturn(new CliSession.CommandResult("changed.js", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "git diff {{ branch }} --name-only");
        config.put("command", "lint {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeShellCommandOnHost("/test/project", "git diff main --name-only", 300);
    }

    @Test
    void execute_noItemsSource_defaultsToList() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        // items_source omitted — should default to "list"
        config.put("items", List.of("one", "two"));
        config.put("command", "handle {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_eachIteration_seesIsolatedItemVariable() {
        // Context isolation: each iteration may only see its own item,
        // never the one from the previous iteration. (item N != item N-1)
        ArrayList<String> capturedItems = new ArrayList<>();

        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenAnswer(inv -> {
                    Object current = context.getVariable("item");
                    capturedItems.add(String.valueOf(current));
                    return StepResult.builder()
                            .stepIndex(0)
                            .status(StepStatus.COMPLETED)
                            .output("ok")
                            .build();
                });

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("alpha", "beta", "gamma"));
        config.put("variable_name", "item");
        config.put("command", "echo {{ item }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(capturedItems).containsExactly("alpha", "beta", "gamma");
        // item N != item N-1 — each iteration overwrites the variable cleanly
        assertThat(capturedItems.get(1)).isNotEqualTo(capturedItems.get(0));
        assertThat(capturedItems.get(2)).isNotEqualTo(capturedItems.get(1));
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }


    @Test
    void execute_shellMode_untrustedVariable_isEscapedInItemsCommandAndCommand() {
        context.setUntrustedVariable("dir", "src; id");
        when(commandExecutor.executeShellCommandOnHost("/test/project", "ls 'src; id'", 300))
                .thenReturn(new CliSession.CommandResult("a.txt", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "ls {{ dir }}");
        config.put("command", "cat {{ dir }}/{{ item }}");
        config.put("execution_mode", "shell");
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        org.mockito.ArgumentCaptor<BlockDefinition> captor = org.mockito.ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(eq("/test/project"), captor.capture(), anyInt(), eq(300));
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo("cat 'src; id'/'a.txt'");
    }

    @Test
    void execute_shellMode_platformDataInItemsCommand_escapesTheItems() {
        // previous_report is quoted in the items_command, but its content becomes the items —
        // substituting it into a shell path taints the run, so the items are escaped too
        context.setUntrustedPlatformVariable("previous_report", "report");
        when(commandExecutor.executeShellCommandOnHost("/test/project", "echo 'report'", 300))
                .thenReturn(new CliSession.CommandResult("a.txt; id", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "command");
        config.put("items_command", "echo {{ previous_report }}");
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        org.mockito.ArgumentCaptor<BlockDefinition> captor = org.mockito.ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(eq("/test/project"), captor.capture(), anyInt(), eq(300));
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo("cat 'a.txt; id'");
    }

    @Test
    void execute_shellMode_staticItems_stayRawInATaintedRun() {
        context.setUntrustedVariable("dir", "src");   // run is tainted, but the items are static
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a.txt"));
        config.put("command", "cat {{ item }}");
        config.put("execution_mode", "shell");
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        org.mockito.ArgumentCaptor<BlockDefinition> captor = org.mockito.ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(eq("/test/project"), captor.capture(), anyInt(), eq(300));
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo("cat a.txt");
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("CODEX");
        context.setModel("gpt-5");
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(stepResult);

        Map<String, Object> config = new HashMap<>();
        config.put("items_source", "list");
        config.put("items", List.of("a"));
        config.put("command", "process {{ item }}");
        config.put("provider", "PI");
        config.put("model", "anthropic/opus");

        BlockDefinition block = BlockDefinition.builder()
                .type("foreach")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_carriesAiRequestFromFirstItemAndRecordsItems() {
        StepResult first = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("a").durationMs(10L).build();
        first.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        StepResult second = StepResult.builder().stepIndex(2).status(StepStatus.FAILED).output("b").build();
        second.setMetadata(Map.of("aiRequest", Map.of("provider", "PI"), "provider", "PI"));
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(first, second);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("alpha", "x".repeat(200)));
        config.put("command", "process {{ item }}");
        config.put("continue_on_error", true);
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata())
                .containsEntry("provider", "CLAUDE")
                .containsEntry("requestedModel", "opus")
                .containsEntry("totalItems", 2)
                .containsEntry("failedItemCount", 1);
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
        List<Map<String, Object>> items = (List<Map<String, Object>>) result.getMetadata().get("items");
        assertThat(items).hasSize(2);
        assertThat(items.get(0)).containsEntry("index", 0).containsEntry("item", "alpha")
                .containsEntry("status", "COMPLETED").containsEntry("durationMs", 10L);
        assertThat(items.get(1)).containsEntry("status", "FAILED").containsEntry("durationMs", 0L);
        String label = (String) items.get(1).get("item");
        assertThat(label).hasSize(123).endsWith("...");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_shellMode_recordsItemsWithoutAiRequest() {
        StepResult shellStep = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        shellStep.setMetadata(Map.of("exitCode", 0, "executionMode", "shell"));
        when(commandExecutor.executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), anyInt()))
                .thenReturn(shellStep);

        Map<String, Object> config = new HashMap<>();
        config.put("items", List.of("a", "b", "c"));
        config.put("command", "echo {{ item }}");
        config.put("execution_mode", "shell");
        BlockDefinition block = BlockDefinition.builder().type("foreach").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getMetadata()).doesNotContainKey("aiRequest").containsEntry("totalItems", 3);
        assertThat((List<Map<String, Object>>) result.getMetadata().get("items")).hasSize(3);
    }
}
