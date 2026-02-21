package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.exception.WorkflowExecutionException;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillCapabilityBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private SkillCapabilityBlockExecutor executor;

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
    void supports_skillCapabilityType() {
        assertThat(executor.supports("skill-capability")).isTrue();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_validCapability_returnsCompletedResult() {
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("fact_check"), any(), any()))
                .thenReturn(new CliSession.EnrichedCommandResult("Facts verified: 95% accuracy", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "fact_check");
        config.put("input", "The earth is round.");
        config.put("params", Map.of("strictness", "high"));

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("95% accuracy");
        assertThat(result.getInput()).contains("fact_check");
    }

    @Test
    void execute_resolvesInputTemplate() {
        context.setPreviousOutput("document content here");
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("document content here"), any(), any()))
                .thenReturn(new CliSession.EnrichedCommandResult("analyzed", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "analyze");
        config.put("input", "{{ previous_output }}");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    @Test
    void execute_commandThrows_returnsFailedResult() {
        when(commandExecutor.executeCommandOnHost(anyString(), anyString(), any(), any()))
                .thenThrow(new RuntimeException("AI unavailable"));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "ocr");
        config.put("input", "image data");

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("AI unavailable");
    }

    @Test
    void execute_nullCapability_throwsException() {
        Map<String, Object> config = new HashMap<>();
        config.put("input", "some input");
        // No "capability" key

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        assertThatThrownBy(() -> executor.execute(block, context))
                .isInstanceOf(WorkflowExecutionException.class)
                .hasMessageContaining("capability");
    }

    @Test
    void execute_noInput_usesEmptyString() {
        when(commandExecutor.executeCommandOnHost(anyString(), anyString(), any(), any()))
                .thenReturn(new CliSession.EnrichedCommandResult("processed", 0, null));

        Map<String, Object> config = new HashMap<>();
        config.put("capability", "summarize");
        // No input

        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }
}
