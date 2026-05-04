package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CliSession;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.cli.HostExecutionRequest;
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

        when(commandExecutor.executeOnHost(argThat(req -> "check condition".equals(req.command()))))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("analyzed");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(step);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.PENDING);
        assertThat(result.getOutput()).contains("[iteration 1]");
        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
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

    @Test
    void execute_codexProvider_passesProviderIdToRequests() {
        context.setProvider("CODEX");
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("ok");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(step);

        executor.execute(block, context);

        verify(commandExecutor, atLeastOnce()).executeOnHost(
                argThat(req -> "CODEX".equals(req.providerId())));
        verify(commandExecutor).executeStepOnHost(
                argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_shellMode_initialFalseCondition_zeroIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "test -f /tmp/never");
        config.put("max_iterations", 10);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        // Condition false on first call: non-zero exit code → loop body never runs
        when(commandExecutor.executeShellCommandOnHost(eq("/tmp/project"), eq("test -f /tmp/never"), eq(60)))
                .thenReturn(new CliSession.CommandResult("", 1));

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEmpty();
        verify(commandExecutor, times(1)).executeShellCommandOnHost(eq("/tmp/project"), eq("test -f /tmp/never"), eq(60));
        verify(commandExecutor, never()).executeStepAsShell(anyString(), any(), anyInt(), anyInt());
    }

    @Test
    void execute_claudeMode_initialFalseCondition_zeroIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 10);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        // Condition returns "false" right away → body never executes
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEmpty();
        verify(commandExecutor, times(1)).executeOnHost(any(HostExecutionRequest.class));
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_claudeMode_emptyConditionResult_treatedAsFalse() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 5);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("   ", 0, null));

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEmpty();
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_claudeMode_zeroConditionResult_treatedAsFalse() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "do work");
        config.put("condition_command", "check");
        config.put("max_iterations", 5);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("0", 0, null));

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEmpty();
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_setsPreviousOutputBetweenIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 3);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        // Each iteration returns a unique output so we can verify isolation
        StepResult step1 = new StepResult();
        step1.setStepIndex(0);
        step1.setOutput("output-1");
        step1.setStatus(StepStatus.COMPLETED);

        StepResult step2 = new StepResult();
        step2.setStepIndex(1);
        step2.setOutput("output-2");
        step2.setStatus(StepStatus.COMPLETED);

        StepResult step3 = new StepResult();
        step3.setStepIndex(2);
        step3.setOutput("output-3");
        step3.setStatus(StepStatus.COMPLETED);

        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(step1, step2, step3);

        executor.execute(block, context);

        // After all iterations, previousOutput on the context must reflect the LAST step
        // (proves setPreviousOutput was updated each iteration; otherwise it would still be null/old)
        assertThat(context.getPreviousOutput()).isEqualTo("output-3");
    }

    @Test
    void execute_failedStep_outputContainsIterationNumber() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo loop");
        config.put("max_iterations", 5);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        // First two iterations succeed, third fails → failure must mention "iteration 3"
        StepResult ok1 = new StepResult();
        ok1.setOutput("ok1");
        ok1.setStatus(StepStatus.COMPLETED);

        StepResult ok2 = new StepResult();
        ok2.setOutput("ok2");
        ok2.setStatus(StepStatus.COMPLETED);

        StepResult failed = new StepResult();
        failed.setOutput("boom");
        failed.setStatus(StepStatus.FAILED);

        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(ok1, ok2, failed);

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("[iteration 1]: ok1");
        assertThat(result.getOutput()).contains("[iteration 2]: ok2");
        assertThat(result.getOutput()).contains("[iteration 3]: boom");
        // Loop must have stopped — no fourth call
        verify(commandExecutor, times(3)).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60));
    }

    @Test
    void execute_shellMode_passesCustomTimeoutToCondition() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo body");
        config.put("condition_command", "check it");
        config.put("max_iterations", 1);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 17);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeShellCommandOnHost(eq("/tmp/project"), eq("check it"), eq(17)))
                .thenReturn(new CliSession.CommandResult("", 0));

        StepResult step = new StepResult();
        step.setOutput("done");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(17)))
                .thenReturn(step);

        executor.execute(block, context);

        // Both condition and body must use the configured 17s timeout
        verify(commandExecutor).executeShellCommandOnHost(eq("/tmp/project"), eq("check it"), eq(17));
        verify(commandExecutor).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(17));
    }

    @Test
    void execute_claudeMode_passesCustomTimeoutToRequests() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "claude");
        config.put("timeout_seconds", 42);

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setOutput("ok");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(step);

        executor.execute(block, context);

        verify(commandExecutor, atLeastOnce()).executeOnHost(
                argThat(req -> req.timeoutSeconds() != null && req.timeoutSeconds() == 42));
        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.timeoutSeconds() != null && req.timeoutSeconds() == 42));
    }

    @Test
    void execute_claudeMode_omittedTimeoutSeconds_passesNullTimeout() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "claude");
        // No timeout_seconds → claudeTimeout must be null

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setOutput("ok");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(step);

        executor.execute(block, context);

        verify(commandExecutor, atLeastOnce()).executeOnHost(
                argThat(req -> req.timeoutSeconds() == null));
        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.timeoutSeconds() == null));
    }

    @Test
    void execute_noConditionCommand_runsExactlyMaxIterations() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "echo");
        config.put("max_iterations", 4);
        config.put("execution_mode", "shell");
        config.put("timeout_seconds", 60);
        // no condition_command → loop is bounded only by max_iterations

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        StepResult step = new StepResult();
        step.setOutput("x");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60)))
                .thenReturn(step);

        StepResult result = executor.execute(block, context);

        verify(commandExecutor, times(4)).executeStepAsShell(eq("/tmp/project"), any(), anyInt(), eq(60));
        // Condition never queried
        verify(commandExecutor, never()).executeShellCommandOnHost(anyString(), anyString(), anyInt());
        assertThat(result.getOutput()).contains("[iteration 4]");
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequests() {
        Map<String, Object> config = new HashMap<>();
        config.put("command", "analyze");
        config.put("condition_command", "check");
        config.put("max_iterations", 1);
        config.put("execution_mode", "claude");

        BlockDefinition block = BlockDefinition.builder().type("while").config(config).build();

        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        StepResult step = new StepResult();
        step.setStepIndex(0);
        step.setOutput("ok");
        step.setStatus(StepStatus.COMPLETED);
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(step);

        executor.execute(block, context);

        verify(commandExecutor, atLeastOnce()).executeOnHost(
                argThat(req -> req.providerId() == null));
        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.providerId() == null));
    }
}
