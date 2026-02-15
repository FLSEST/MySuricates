package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RetryBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private RetryBlockExecutor executor;

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
    void supports_retryType() {
        assertThat(executor.supports("retry")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_successOnFirstAttempt_returnsCompleted() {
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("passed").build();
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(), anyInt()))
                .thenReturn(success);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 3,
                        "delay", 0
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(1)).executeStep(any(), any(), any(), anyInt());
    }

    @Test
    void execute_failsThenSucceeds_retriesAndReturnsCompleted() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("failed").build();
        StepResult success = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("passed").build();
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(), anyInt()))
                .thenReturn(failed, success);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 3,
                        "delay", 0
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStep(any(), any(), any(), anyInt());
    }

    @Test
    void execute_allAttemptsFail_returnsLastFailedResult() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(), anyInt()))
                .thenReturn(failed);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 2,
                        "delay", 0
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        // 1 initial + 2 retries = 3 attempts
        verify(commandExecutor, times(3)).executeStep(any(), any(), any(), anyInt());
    }

    @Test
    void execute_cancelledDuringRetry_stopsEarly() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(), anyInt()))
                .thenAnswer(inv -> {
                    context.cancel(); // Cancel after first attempt
                    return failed;
                });

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 5,
                        "delay", 0
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commandExecutor, times(1)).executeStep(any(), any(), any(), anyInt());
    }

    @Test
    void execute_defaultRetries_usesThree() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStep(eq("container-1"), eq("claude"), any(), anyInt()))
                .thenReturn(failed);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "delay", 0
                )))
                .build();

        executor.execute(block, context);

        // 1 initial + 3 default retries = 4 attempts
        verify(commandExecutor, times(4)).executeStep(any(), any(), any(), anyInt());
    }
}
