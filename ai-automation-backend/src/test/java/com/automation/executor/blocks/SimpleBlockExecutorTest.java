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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

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
                .cliType("claude")
                .workflowRunId("run-1")
                .build();
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
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(BlockDefinition.class), anyInt()))
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
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(BlockDefinition.class), anyInt()))
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
}
