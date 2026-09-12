package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.ContainerShellService;
import com.automation.service.cli.ShellCommandService;
import com.automation.service.cli.ShellCommandService.ShellResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ShellBlockExecutorTest {

    @Mock
    private ShellCommandService shellService;

    @Mock
    private ContainerShellService containerShellService;

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

    @Test
    void execute_shellEscapeVariables_escapesValues() {
        context.setVariable("name", "it's a \"test\"");
        when(shellService.execute(eq("echo 'it'\\''s a \"test\"'"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}", "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_default_doesNotEscape() {
        context.setVariable("name", "it's a \"test\"");
        when(shellService.execute(eq("echo it's a \"test\""), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ name }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_preventsInjection() {
        context.setVariable("input", "; rm -rf /");
        when(shellService.execute(eq("process '; rm -rf /'"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("safe", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "process {{ input }}", "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_shellEscapeVariables_envPrefixBeforeHeredoc_quotesOnlyTheShellLine() {
        // Pattern used by the github-issues templates: the value travels via an env assignment
        // on the shell line; the quoted heredoc body must stay byte-identical.
        context.setVariable("project_id", "abc");
        when(shellService.execute(eq("PROJECT_ID='abc' python3 - <<'PY'\nimport os\nprint(os.environ['PROJECT_ID'])\nPY"),
                eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("abc", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "PROJECT_ID={{ project_id }} python3 - <<'PY'\nimport os\nprint(os.environ['PROJECT_ID'])\nPY",
                        "shell_escape_variables", true)))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_containerImageProvided_routesToContainerShellService() {
        when(containerShellService.execute(eq("echo hi"), eq("/test/project"), eq(300),
                eq("python:3.12"), eq(false), eq(List.of())))
                .thenReturn(new ShellResult("hi", 0, 20));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi", "container_image", "python:3.12")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("executionMode", "container:python:3.12");
        verify(shellService, never()).execute(any(), any(), anyInt(), any());
    }

    @Test
    void execute_containerImageBlank_fallsBackToShell() {
        when(shellService.execute(eq("echo hi"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("hi", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo hi", "container_image", "  ")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("executionMode", "shell");
        verify(containerShellService, never()).execute(any(), any(), anyInt(), any(), anyBoolean(), any());
    }

    @Test
    void execute_mountDockerSocket_propagatedToContainerService() {
        when(containerShellService.execute(eq("docker ps"), eq("/test/project"), eq(300),
                eq("automation-agent-devops:latest"), eq(true), eq(List.of())))
                .thenReturn(new ShellResult("containers", 0, 30));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "docker ps",
                        "container_image", "automation-agent-devops:latest",
                        "mount_docker_socket", true
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(containerShellService).execute(any(), any(), anyInt(), any(), eq(true), any());
    }

    @Test
    void execute_mountsList_resolvedAndPassedToContainer() {
        context.setVariable("home", "/test/user");
        List<String> expectedMounts = List.of("/test/user/data:/data:ro", "/tmp:/tmp");

        when(containerShellService.execute(eq("ls /data"), eq("/test/project"), eq(300),
                eq("python:3.12"), eq(false), eq(expectedMounts)))
                .thenReturn(new ShellResult("files", 0, 15));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of(
                        "command", "ls /data",
                        "container_image", "python:3.12",
                        "mounts", List.of("{{ home }}/data:/data:ro", "/tmp:/tmp")
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(containerShellService).execute(any(), any(), anyInt(), any(), anyBoolean(), eq(expectedMounts));
    }

    @Test
    void execute_nonZeroExit_outputContainsRecoveryHint() {
        when(shellService.execute(eq("bad-cmd"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("error msg", 1, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "bad-cmd")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[RECOVERY]");
    }

    @Test
    void execute_registersAndClearsProcessForShellPath() {
        when(shellService.execute(any(), any(), anyInt(), any()))
                .thenReturn(new ShellResult("out", 0, 10));

        ExecutionContext contextSpy = spy(context);

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo test")))
                .build();

        executor.execute(block, contextSpy);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Consumer<Process>> consumerCaptor = ArgumentCaptor.forClass(Consumer.class);
        verify(shellService).execute(any(), any(), anyInt(), consumerCaptor.capture());

        Process mockProcess = mock(Process.class);
        consumerCaptor.getValue().accept(mockProcess);

        verify(contextSpy).registerProcess(mockProcess);
        verify(contextSpy).clearProcess();
    }


    @Test
    void execute_untrustedVariable_isEscapedWithoutOptIn() {
        context.setUntrustedVariable("branch", "main; rm -rf /");
        when(shellService.execute(eq("git checkout 'main; rm -rf /'"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "git checkout {{ branch }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getInput()).isEqualTo("git checkout 'main; rm -rf /'");
    }

    @Test
    void execute_trustedVariable_staysRawWithoutOptIn() {
        context.setVariable("branch", "main");
        when(shellService.execute(eq("git checkout main"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "git checkout {{ branch }}")))
                .build();

        executor.execute(block, context);

        verify(shellService).execute(eq("git checkout main"), eq("/test/project"), eq(300), any());
    }

    @Test
    void execute_taintedPreviousOutput_isEscapedWithoutOptIn() {
        context.setUntrustedVariable("branch", "main");   // webhook payload — the run is tainted
        context.recordOutput("scan", "x; id");
        when(shellService.execute(eq("echo 'x; id'"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ previous_output }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getInput()).isEqualTo("echo 'x; id'");
    }

    @Test
    void execute_cleanRun_blockOutputStaysRaw() {
        context.recordOutput("scan", "a | b");
        when(shellService.execute(eq("echo a | b"), eq("/test/project"), eq(300), any()))
                .thenReturn(new ShellResult("ok", 0, 10));

        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(new HashMap<>(Map.of("command", "echo {{ block.scan.output }}")))
                .build();

        assertThat(executor.execute(block, context).getInput()).isEqualTo("echo a | b");
    }
}
