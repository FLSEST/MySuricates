package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ForEachBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

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
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        verify(commandExecutor, times(3)).executeStepOnHost(any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_withCommandSource_usesCommandOutput() {
        when(commandExecutor.executeCommandOnHost("/test/project", "git diff --name-only"))
                .thenReturn(new CliSession.CommandResult("file1.js\nfile2.js", 0));
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        verify(commandExecutor, times(2)).executeStepOnHost(any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_anyItemFails_returnsFailedStatus() {
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        StepResult failed = StepResult.builder()
                .stepIndex(1).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        verify(commandExecutor, never()).executeStepOnHost(any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_cancelledDuringIteration_stopsEarly() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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

        verify(commandExecutor, times(1)).executeStepOnHost(any(), any(), anyInt(), any(), any());
    }

    @Test
    void execute_stringItems_splitsByNewline() {
        StepResult stepResult = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        verify(commandExecutor, times(3)).executeStepOnHost(any(), any(), anyInt(), any(), any());
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
        verify(commandExecutor, never()).executeStepOnHost(any(), any(), anyInt(), any(), any());
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
}
