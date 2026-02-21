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
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("Evaluate")))
                .thenReturn(new CliSession.CommandResult("true", 0));
        StepResult thenResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("deployed").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("Evaluate")))
                .thenReturn(new CliSession.CommandResult("false", 0));
        StepResult elseResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("skipped").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
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
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("Evaluate")))
                .thenReturn(new CliSession.CommandResult("false", 0));

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
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("Evaluate")))
                .thenReturn(new CliSession.CommandResult("The condition is false, not true", 0));

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
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull()))
                .thenReturn(elseResult);

        StepResult result = executor.execute(block, context);

        // Should evaluate as false (execute else branch), not true
        assertThat(result.getInput()).contains("false");
        assertThat(result.getOutput()).isEqualTo("skipped");
    }

    @Test
    void execute_conditionFalse_noThenDefined_butThenWouldExecute() {
        when(commandExecutor.executeCommandOnHost(eq("/test/project"), contains("Evaluate")))
                .thenReturn(new CliSession.CommandResult("true", 0));

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
}
