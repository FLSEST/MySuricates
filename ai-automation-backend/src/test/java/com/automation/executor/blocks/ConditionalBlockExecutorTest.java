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
class ConditionalBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private ConditionalBlockExecutor executor;

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
    void supports_conditionalType() {
        assertThat(executor.supports("conditional")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_conditionTrue_executesThenBranch() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("Evaluate"))))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("deployed").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("deployed");
        assertThat(result.getInput()).contains("true");
    }

    @Test
    void execute_conditionFalse_executesElseBranch() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("Evaluate"))))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));
        StepResult elseResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("skipped").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(elseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEqualTo("skipped");
    }

    @Test
    void execute_conditionTrue_noElseBranch_skips() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("Evaluate"))))
                .thenReturn(new CliSession.EnrichedCommandResult("false", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "deploy");
        // No else_command

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("else");
    }

    @Test
    void execute_ambiguousResponse_notTreatedAsTrue() {
        // Edge case: response contains "true" but means false ("The condition is false, not true")
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("Evaluate"))))
                .thenReturn(new CliSession.EnrichedCommandResult("The condition is false, not true", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == 'main'");
        config.put("then_command", "npm run deploy");
        config.put("else_command", "echo 'skipping'");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        StepResult elseResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("skipped").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(elseResult);

        StepResult result = executor.execute(block, context);

        // Should evaluate as false (execute else branch), not true
        assertThat(result.getInput()).contains("false");
        assertThat(result.getOutput()).isEqualTo("skipped");
    }

    @Test
    void execute_conditionFalse_noThenDefined_butThenWouldExecute() {
        when(commandExecutor.executeOnHost(argThat(req -> req.command().contains("Evaluate"))))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "always true");
        // No then_command
        config.put("else_command", "echo 'else'");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("then");
    }

    @Test
    void execute_codexProvider_passesProviderIdToRequests() {
        context.setProvider("CODEX");
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x == 1");
        config.put("then_command", "do something");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> "CODEX".equals(req.providerId())));
        verify(commandExecutor).executeStepOnHost(argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequests() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("true", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x == 1");
        config.put("then_command", "do something");

        BlockDefinition block = BlockDefinition.builder()
                .type("conditional")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeOnHost(argThat(req -> req.providerId() == null));
        verify(commandExecutor).executeStepOnHost(argThat(req -> req.providerId() == null));
    }

    @Test
    void execute_shellMode_exitCodeZero_executesThenBranch() {
        when(commandExecutor.executeShellCommandOnHost(eq("/test/project"), eq("test -f /tmp/x"), anyInt()))
                .thenReturn(new CliSession.CommandResult("", 0));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("then-shell-output").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(BlockDefinition.class), anyInt(), anyInt()))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test -f /tmp/x");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("then-shell-output");
        assertThat(result.getInput()).contains("true");
        verify(commandExecutor, never()).executeOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_shellMode_nonZeroExit_executesElseBranch() {
        when(commandExecutor.executeShellCommandOnHost(anyString(), anyString(), anyInt()))
                .thenReturn(new CliSession.CommandResult("", 1));
        StepResult elseResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("else-shell").build();
        when(commandExecutor.executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), anyInt()))
                .thenReturn(elseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "test -f /tmp/x");
        config.put("then_command", "echo then");
        config.put("else_command", "echo else");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEqualTo("else-shell");
        assertThat(result.getInput()).contains("false");
    }

    @Test
    void execute_shellMode_falsy_noElseDefined_skips() {
        when(commandExecutor.executeShellCommandOnHost(anyString(), anyString(), anyInt()))
                .thenReturn(new CliSession.CommandResult("", 1));

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "false");
        config.put("then_command", "echo then");
        // no else_command

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.SKIPPED);
        assertThat(result.getOutput()).contains("else");
        verify(commandExecutor, never()).executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), anyInt());
    }

    @Test
    void execute_resolvesVariablesInCondition() {
        context.setVariable("branch", "main");
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("TRUE", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "branch == '{{ branch }}'");
        config.put("then_command", "deploy");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        // Verify the condition was resolved before being sent to Claude
        verify(commandExecutor).executeOnHost(argThat(req ->
                req.command().contains("branch == 'main'") && !req.command().contains("{{ branch }}")));
        assertThat(result.getInput()).contains("branch == 'main'");
    }

    @Test
    void execute_resolvesVariablesInThenCommand() {
        context.setVariable("env", "production");
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("TRUE", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "always");
        config.put("then_command", "deploy to {{ env }}");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        executor.execute(block, context);

        // Verify the then_command was template-resolved before being passed
        verify(commandExecutor).executeStepOnHost(argThat(req -> {
            Object cmd = req.block().getConfig().get("command");
            return cmd != null && cmd.toString().equals("deploy to production");
        }));
    }

    @Test
    void execute_customTimeout_passedToShellExecutor() {
        when(commandExecutor.executeShellCommandOnHost(anyString(), anyString(), eq(99)))
                .thenReturn(new CliSession.CommandResult("", 0));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), eq(99)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", "shell");
        config.put("condition", "true");
        config.put("then_command", "echo ok");
        config.put("timeout_seconds", 99);

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        executor.execute(block, context);

        verify(commandExecutor).executeShellCommandOnHost(anyString(), anyString(), eq(99));
        verify(commandExecutor).executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), eq(99));
    }

    @Test
    void execute_customTimeout_passedToClaudeExecutor() {
        when(commandExecutor.executeOnHost(argThat(req -> req != null && req.timeoutSeconds() != null && req.timeoutSeconds() == 77)))
                .thenReturn(new CliSession.EnrichedCommandResult("TRUE", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x == 1");
        config.put("then_command", "do");
        config.put("timeout_seconds", 77);

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(argThat(req ->
                req.timeoutSeconds() != null && req.timeoutSeconds() == 77));
    }

    @Test
    void execute_truthyResponseWithWhitespaceAndLowercase_treatedAsTrue() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("  true\n", 0, null));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("then-ran").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(thenResult);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x");
        config.put("then_command", "do");
        config.put("else_command", "skip");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).isEqualTo("then-ran");
        assertThat(result.getInput()).contains("true");
    }

    @Test
    void execute_setsDurationAndTimestamps() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("FALSE", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x");
        config.put("then_command", "do");
        // no else → SKIPPED

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getStartedAt()).isNotNull();
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getDurationMs()).isGreaterThanOrEqualTo(0);
        assertThat(result.getCompletedAt()).isAfterOrEqualTo(result.getStartedAt());
    }

    @Test
    void execute_propagatesFailedStepStatus() {
        when(commandExecutor.executeOnHost(any(HostExecutionRequest.class)))
                .thenReturn(new CliSession.EnrichedCommandResult("TRUE", 0, null));
        StepResult failedThen = StepResult.builder()
                .stepIndex(1).status(StepStatus.FAILED).output("error: deploy broken").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(failedThen);

        Map<String, Object> config = new HashMap<>();
        config.put("condition", "x");
        config.put("then_command", "deploy");

        BlockDefinition block = BlockDefinition.builder().type("conditional").config(config).build();
        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("error");
    }
}
