package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
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
                .workflowRunId("run-1")
                .build();
        context.setProjectPath("/test/project");
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_failsThenSucceeds_retriesAndReturnsCompleted() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("failed").build();
        StepResult success = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("passed").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_allAttemptsFail_returnsLastFailedResult() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(3)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_cancelledDuringRetry_stopsEarly() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_defaultRetries_usesThree() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(4)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_shellMode_usesShellExecution() {
        StepResult failed = StepResult.builder()
                .stepIndex(0).status(StepStatus.FAILED).output("error").build();
        StepResult success = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("passed").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(failed, success);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 3,
                        "delay", 0,
                        "execution_mode", "shell"
                )))
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        verify(commandExecutor, times(2)).executeStepAsShell(any(), any(), anyInt(), eq(300));
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_codexProvider_passesProviderIdToRequest() {
        context.setProvider("CODEX");
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(success);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 1,
                        "delay", 0
                )))
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        StepResult success = StepResult.builder()
                .stepIndex(0).status(StepStatus.COMPLETED).output("ok").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(success);

        BlockDefinition block = BlockDefinition.builder()
                .type("retry")
                .config(new HashMap<>(Map.of(
                        "command", "npm test",
                        "max_retries", 1,
                        "delay", 0
                )))
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.providerId() == null));
    }
}
