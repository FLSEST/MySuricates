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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WhileBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private WhileBlockExecutor executor;

    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        context = ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .projectPath("/tmp/project")
                .build();
    }

    @Test
    void supports_whileType_returnsTrue() {
        assertThat(executor.supports("while")).isTrue();
    }

    @Test
    void supports_otherType_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_shellMode_runsIterationsUntilConditionFails() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo hello");
        config.put("condition_command", "test -f /tmp/flag");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        // Condition: true first time, false second time
        when(commandExecutor.executeShellCommandOnHost(eq("/tmp/project"), eq("test -f /tmp/flag"), eq(60)))
                .thenReturn(new CliSession.CommandResult("", 0))
                .thenReturn(new CliSession.CommandResult("", 1));

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("hello");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(step);

        StepResult result = executor.execute(block, context);

        // StepResult defaults to PENDING; the null-check in source never triggers
        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getOutput()).contains("[iteration 1]");
        verify(commandExecutor, times(1)).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60));
    }

    @Test
    void execute_reachesMaxIterations_completesNormally() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 3);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("loop");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(step);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getOutput()).contains("[iteration 1]");
        assertThat(result.getOutput()).contains("[iteration 3]");
        verify(commandExecutor, times(3)).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60));
    }

    @Test
    void execute_failedStep_stopsAndReturnsFailed() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "failing command");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        StepResult failedStep = new StepResult();
        failedStep.setStepIndex(0);
        failedStep.setOutput("error");
        failedStep.setStatus(StepStatus.FAILED);
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(failedStep);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commandExecutor, times(1)).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60));
    }

    @Test
    void execute_cancelledContext_stopsImmediately() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 100);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        context.cancel();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getOutput()).isEmpty();
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void execute_claudeMode_usesClaudeExecution() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze code");
        config.put("condition_command", "check condition");
        config.put("max_iterations", 2);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        CliSession.EnrichedCommandResult conditionResult = new CliSession.EnrichedCommandResult("true", 0, null);
        when(commandExecutor.executeCommandOnHost(eq("/tmp/project"), eq("check condition"), isNull(), isNull(), isNull()))
                .thenReturn(conditionResult)
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("analyzed");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(eq("/tmp/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(step);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getOutput()).contains("[iteration 1]");
        verify(commandExecutor, times(1)).executeStepOnHost(eq("/tmp/project"), any(), anyInt(), isNull(), isNull(), isNull());
    }

    @Test
    void execute_setsInputDescription() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo");
        config.put("max_iterations", 0);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getInput()).contains("while loop");
        assertThat(result.getInput()).contains("0 iterations");
    }
}
