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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SwitchBlockExecutorTest {

    @Mock
    private CommandExecutorService commandExecutor;

    @InjectMocks
    private SwitchBlockExecutor executor;

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
    void supports_switchType() {
        assertThat(executor.supports("switch")).isTrue();
        assertThat(executor.supports("conditional")).isFalse();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void execute_expressionResolvesFromContextVariable() {
        context.setVariable("status", "success");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("deployed").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy app"))
                )),
                Map.of("value", "failure", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "rollback"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("deployed");
        assertThat(result.getInput()).isEqualTo("switch (success)");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "success");
    }

    @Test
    void execute_exactMatchSelectsCorrectCase() {
        context.setVariable("env", "staging");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("staging deploy done").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${env}");
        config.put("cases", List.of(
                Map.of("value", "production", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy prod"))
                )),
                Map.of("value", "staging", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy staging"))
                )),
                Map.of("value", "development", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy dev"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("staging deploy done");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "staging");
    }

    @Test
    void execute_pipeSeparatedValuesMatch() {
        context.setVariable("status", "critical");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("alert sent").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "log success"))
                )),
                Map.of("value", "error|critical", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "send alert"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("alert sent");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "error|critical");
    }

    @Test
    void execute_defaultCaseWhenNoMatch() {
        context.setVariable("status", "unknown");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("default handler executed").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "celebrate"))
                ))
        ));
        config.put("default", List.of(
                Map.of("type", "simple", "config", Map.of("command", "handle unknown"))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("default handler executed");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "default");
    }

    @Test
    void execute_noDefaultAndNoMatch_completedWithNoMatchOutput() {
        context.setVariable("status", "unknown");

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "success", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "celebrate"))
                ))
        ));
        // No default

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("No matching case");
        assertThat(result.getOutput()).contains("unknown");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commandExecutor, never()).executeStepOnHost(any(), any(), anyInt(), any(), any(), any());
        verify(commandExecutor, never()).executeStepAsShell(any(), any(), anyInt(), anyInt());
    }

    @Test
    void execute_nestedBlocksExecuteCorrectly() {
        context.setVariable("action", "build");

        StepResult firstBlock = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("compiled").build();
        StepResult secondBlock = StepResult.builder()
                .stepIndex(2).status(StepStatus.COMPLETED).output("tested").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(firstBlock, secondBlock);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${action}");
        config.put("cases", List.of(
                Map.of("value", "build", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "mvn compile")),
                        Map.of("type", "simple", "config", Map.of("command", "mvn test"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("compiled");
        assertThat(result.getOutput()).contains("tested");
        verify(commandExecutor, times(2)).executeStepOnHost(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void execute_shellModeNestedBlocks() {
        context.setVariable("mode", "cleanup");

        StepResult shellResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("cleaned up").build();
        when(commandExecutor.executeStepAsShell(eq("/test/project"), any(), anyInt(), eq(300)))
                .thenReturn(shellResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${mode}");
        config.put("cases", List.of(
                Map.of("value", "cleanup", "blocks", List.of(
                        Map.of("type", "shell", "config", Map.of("command", "rm -rf tmp/"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("cleaned up");
        verify(commandExecutor).executeStepAsShell(any(), any(), anyInt(), eq(300));
        verify(commandExecutor, never()).executeStepOnHost(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void execute_nestedBlockFailure_propagatesFailedStatus() {
        context.setVariable("status", "deploy");

        StepResult failedResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.FAILED).output("deployment error").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(failedResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(
                Map.of("value", "deploy", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "deploy now"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("deployment error");
    }

    @Test
    void execute_doubleBraceExpressionSyntax() {
        context.setVariable("level", "warning");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("warning logged").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ level }}");
        config.put("cases", List.of(
                Map.of("value", "warning", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "log warning"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "warning");
    }

    @Test
    void execute_literalExpression_matchesDirectly() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "always");
        config.put("cases", List.of(
                Map.of("value", "always", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run always"))
                ))
        ));

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("ran").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenReturn(blockResult);

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "always");
    }

    @Test
    void execute_cancelledDuringExecution_stopsEarly() {
        context.setVariable("action", "multi");

        StepResult firstResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("first done").build();
        when(commandExecutor.executeStepOnHost(eq("/test/project"), any(), anyInt(), isNull(), isNull(), isNull()))
                .thenAnswer(inv -> {
                    context.cancel();
                    return firstResult;
                });

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${action}");
        config.put("cases", List.of(
                Map.of("value", "multi", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "step one")),
                        Map.of("type", "simple", "config", Map.of("command", "step two")),
                        Map.of("type", "simple", "config", Map.of("command", "step three"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor, times(1)).executeStepOnHost(any(), any(), anyInt(), any(), any(), any());
    }

    @Test
    void matchesValue_pipeSeparated_matchesAnyAlternative() {
        assertThat(executor.matchesValue("error", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("critical", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("fatal", "error|critical|fatal")).isTrue();
        assertThat(executor.matchesValue("warning", "error|critical|fatal")).isFalse();
    }

    @Test
    void matchesValue_exactMatch() {
        assertThat(executor.matchesValue("success", "success")).isTrue();
        assertThat(executor.matchesValue("success", "failure")).isFalse();
    }

    @Test
    void resolveExpression_dollarSyntax() {
        context.setVariable("myVar", "resolved");
        assertThat(executor.resolveExpression("${myVar}", context)).isEqualTo("resolved");
    }

    @Test
    void resolveExpression_braceSyntax() {
        context.setVariable("myVar", "resolved");
        assertThat(executor.resolveExpression("{{ myVar }}", context)).isEqualTo("resolved");
    }

    @Test
    void resolveExpression_undefinedVariable_resolvesToEmpty() {
        assertThat(executor.resolveExpression("${undefined}", context)).isEmpty();
    }

    @Test
    void resolveExpression_nullExpression_returnsEmpty() {
        assertThat(executor.resolveExpression(null, context)).isEmpty();
    }
}
