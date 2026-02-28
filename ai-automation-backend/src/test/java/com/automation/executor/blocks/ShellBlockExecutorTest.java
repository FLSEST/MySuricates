package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ShellCommandService;
import com.automation.service.cli.ShellCommandService.ShellResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShellBlockExecutorTest {

    @Mock
    private ShellCommandService shellService;

    @InjectMocks
    private ShellBlockExecutor executor;

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
    void supports_shellType() {
        assertThat(executor.supports("shell")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("retry")).isFalse();
    }

    @Test
    void execute_simpleCommand_success() {
        when(shellService.execute(eq("echo hello"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("hello", 0, 50));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hello")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("hello");
        assertThat(result.getDurationMs()).isEqualTo(50);
    }

    @Test
    void execute_failingCommand_returnsFailed() {
        when(shellService.execute(eq("exit 1"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("error", 1, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "exit 1")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    void execute_templateResolution_success() {
        context.setVariable("name", "world");
        when(shellService.execute(eq("echo world"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("world", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("world");
    }

    @Test
    void execute_missingCommand_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("command");
    }

    @Test
    void execute_customTimeout_respected() {
        when(shellService.execute(eq("sleep 1"), eq("/test/project"), eq(60), any()))
                .thenReturn(new ShellResult("", 0, 1000));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "sleep 1", "timeout_seconds", 60)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_setsExecutionModeMetadata() {
        when(shellService.execute(eq("echo test"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("test", 0, 5));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo test")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getMetadata()).containsEntry("executionMode", "shell");
        assertThat(result.getMetadata()).containsEntry("exitCode", 0);
    }
}
