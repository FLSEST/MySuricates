package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
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
}
