package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
import com.automation.executor.ExecutionContext;
import com.automation.service.cli.CommandExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import com.automation.service.cli.HostExecutionRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

@ExtendWith(MockitoExtension.class)
class SimpleBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private SimpleBlockExecutor executor;

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
    void supports_simpleType() {
        assertThat(executor.supports("simple")).isTrue();
        assertThat(executor.supports("retry")).isFalse();
        assertThat(executor.supports("foreach")).isFalse();
    }

    @Test
    void execute_validCommand_delegatesToCommandExecutor() {
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("success").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "echo test")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("success");
    }

    @Test
    void execute_resolvesTemplateVariables() {
        context.setPreviousOutput("previous result");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "process {{ previous_output }}")))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result).isNotNull();
    }

    @Test
    void execute_nullCommand_throwsException() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>())
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("command");
    }

    @Test
    void execute_customTimeout_passedToRequest() {
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test", "timeout_seconds", 120)))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().timeoutSeconds()).isEqualTo(120);
    }

    @Test
    void execute_noTimeout_passesNull() {
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().timeoutSeconds()).isNull();
    }

    @Test
    void execute_passesProviderFromContext() {
        context.setProvider("CODEX");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().providerId()).isEqualTo("CODEX");
    }

    @Test
    void execute_passesModelFromContext() {
        context.setModel("claude-sonnet-4-6");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().model()).isEqualTo("claude-sonnet-4-6");
    }

    @Test
    void execute_noModel_passesNull() {
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().model()).isNull();
    }

    @Test
    void execute_passesInstructionPrefix() {
        context.setInstructionPrefix("You are a test agent");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().systemPrompt()).isEqualTo("You are a test agent");
    }

    @Test
    void execute_blockLevelProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("CODEX");
        context.setModel("gpt-5");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test", "provider", "PI", "model", "anthropic/opus")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().providerId()).isEqualTo("PI");
        assertThat(captor.getValue().model()).isEqualTo("anthropic/opus");
    }

    @Test
    void execute_blockProviderOnly_doesNotCarryWorkflowModelToOtherProvider() {
        context.setProvider("CLAUDE");
        context.setModel("opus");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test", "provider", "PI")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().providerId()).isEqualTo("PI");
        assertThat(captor.getValue().model()).isNull();
    }

    @Test
    void execute_blockModelOnly_keepsWorkflowProvider() {
        context.setProvider("CLAUDE");
        context.setModel("sonnet");
        StepResult expected = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        when(commandExecutor.executeStepOnHost(captor.capture())).thenReturn(expected);

        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test", "model", "opus")))
                .build();

        executor.execute(block, context);

        assertThat(captor.getValue().providerId()).isEqualTo("CLAUDE");
        assertThat(captor.getValue().model()).isEqualTo("opus");
    }
}
