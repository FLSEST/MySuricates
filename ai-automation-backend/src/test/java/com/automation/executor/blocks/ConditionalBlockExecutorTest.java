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
}
