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
import org.mockito.ArgumentCaptor;
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
        verify(commandExecutor, never()).executeStepAsShell(any(), any(), anyInt(), anyInt());
    }

    @Test
    void execute_nestedBlocksExecuteCorrectly() {
        context.setVariable("action", "build");

        StepResult firstBlock = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("compiled").build();
        StepResult secondBlock = StepResult.builder()
                .stepIndex(2).status(StepStatus.COMPLETED).output("tested").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        verify(commandExecutor, times(2)).executeStepOnHost(any(HostExecutionRequest.class));
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
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void execute_nestedBlockFailure_propagatesFailedStatus() {
        context.setVariable("status", "deploy");

        StepResult failedResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.FAILED).output("deployment error").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
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

        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
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

    @Test
    void execute_codexProvider_passesProviderIdToRequest() {
        context.setProvider("CODEX");
        context.setVariable("action", "run");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${action}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do work"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> "CODEX".equals(req.providerId())));
    }

    @Test
    void execute_nullProvider_passesNullProviderIdToRequest() {
        context.setVariable("action", "run");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${action}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do work"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(
                argThat(req -> req.providerId() == null));
    }

    @Test
    void should_executeFirstCase_when_multipleCasesMatch() {
        context.setVariable("flag", "a");

        StepResult firstResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("first branch ran").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(firstResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${flag}");
        config.put("cases", List.of(
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "first command"))
                )),
                Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "second command"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("first branch ran");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "a");
        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void should_executeLastCase_when_onlyLastMatches() {
        context.setVariable("phase", "third");

        StepResult lastResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("third branch ran").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(lastResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${phase}");
        config.put("cases", List.of(
                Map.of("value", "first", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run first"))
                )),
                Map.of("value", "second", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run second"))
                )),
                Map.of("value", "third", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run third"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("third branch ran");
        assertThat(result.getMetadata()).containsEntry("matchedCase", "third");
        verify(commandExecutor, times(1)).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void should_returnSuccess_when_noDefaultAndNoMatch() {
        context.setVariable("state", "irrelevant");

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${state}");
        config.put("cases", List.of(
                Map.of("value", "alpha", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "alpha cmd"))
                )),
                Map.of("value", "beta", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "beta cmd"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
        verify(commandExecutor, never()).executeStepAsShell(any(), any(), anyInt(), anyInt());
    }

    @Test
    void should_notThrowNpe_when_expressionResolvesToNull() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", null);
        config.put("cases", List.of(
                Map.of("value", "some-value", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "run"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void should_notThrowNpe_when_casesIsNull() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "some-value");
        config.put("cases", null);

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void should_notThrowNpe_when_caseValueIsNull() {
        context.setVariable("state", "actual");

        Map<String, Object> nullValueCase = new HashMap<>();
        nullValueCase.put("value", null);
        nullValueCase.put("blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "should not run"))
        ));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${state}");
        config.put("cases", List.of(nullValueCase));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getMetadata()).containsEntry("matchedCase", "none");
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
    }

    @Test
    void should_isolateContextBetweenBranches_when_differentCasesMatchInSequentialRuns() {
        StepResult branchAResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("branch-A-output").build();
        StepResult branchBResult = StepResult.builder()
                .stepIndex(2).status(StepStatus.COMPLETED).output("branch-B-output").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(branchAResult)
                .thenReturn(branchBResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${branch}");
        config.put("cases", List.of(
                Map.of("value", "A", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do A"))
                )),
                Map.of("value", "B", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of("command", "do B"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        context.setVariable("branch", "A");
        StepResult firstResult = executor.execute(block, context);

        context.setVariable("branch", "B");
        StepResult secondResult = executor.execute(block, context);

        assertThat(firstResult.getMetadata()).containsEntry("matchedCase", "A");
        assertThat(firstResult.getOutput()).isEqualTo("branch-A-output");
        assertThat(firstResult.getOutput()).doesNotContain("branch-B-output");

        assertThat(secondResult.getMetadata()).containsEntry("matchedCase", "B");
        assertThat(secondResult.getOutput()).isEqualTo("branch-B-output");
        assertThat(secondResult.getOutput()).doesNotContain("branch-A-output");
    }

    @Test
    void execute_nestedBlockProviderAndModel_overrideWorkflowLevel() {
        context.setProvider("CODEX");
        context.setModel("gpt-5");
        context.setVariable("action", "run");

        StepResult blockResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class)))
                .thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${action}");
        config.put("cases", List.of(
                Map.of("value", "run", "blocks", List.of(
                        Map.of("type", "simple", "config", Map.of(
                                "command", "do work", "provider", "PI", "model", "anthropic/opus"))
                ))
        ));

        BlockDefinition block = BlockDefinition.builder()
                .type("switch")
                .config(config)
                .build();

        executor.execute(block, context);

        verify(commandExecutor).executeStepOnHost(argThat(req ->
                "PI".equals(req.providerId()) && "anthropic/opus".equals(req.model())));
    }

    @Test
    void execute_nestedShellBlock_untrustedVariable_isSingleQuoted() {
        context.setUntrustedVariable("payload", "x; id");
        context.setVariable("status", "success");
        when(commandExecutor.executeStepAsShell(anyString(), any(BlockDefinition.class), anyInt(), anyInt()))
                .thenReturn(StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("ok").build());

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "shell", "config", Map.of("command", "echo {{ payload }}"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(anyString(), captor.capture(), anyInt(), anyInt());
        assertThat(captor.getValue().getConfig().get("command")).isEqualTo("echo 'x; id'");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_carriesAiRequestFromFirstNestedBlock() {
        context.setVariable("status", "success");
        StepResult blockResult = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("deployed").build();
        blockResult.setMetadata(Map.of(
                "aiRequest", Map.of("provider", "CLAUDE", "model", "opus"),
                "provider", "CLAUDE", "requestedModel", "opus"));
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(blockResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "deploy app"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getMetadata())
                .containsEntry("matchedCase", "success")
                .containsEntry("expression", "success")
                .containsEntry("provider", "CLAUDE");
        assertThat((Map<String, Object>) result.getMetadata().get("aiRequest")).containsEntry("model", "opus");
    }

    @Test
    void execute_noMatch_keepsExpressionAndMatchedCaseNoneInMutableMap() {
        context.setVariable("status", "unknown");
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "${status}");
        config.put("cases", List.of(Map.of("value", "success", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "deploy app"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getMetadata()).containsEntry("matchedCase", "none").containsEntry("expression", "unknown");
        result.getMetadata().put("x", 1);
    }

    @Test
    void execute_nestedCompressBlock_isRejectedWithoutAnyCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "compress", "config", Map.of("source", "x", "stages", List.of()))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getOutput()).contains("Blocktyp 'compress' wird in switch nicht unterstützt");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void execute_nestedDocumentBlock_isRejectedNamingTheBlockWithoutAnyCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "document", "name", "report", "config", Map.of("command", "write it"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("document").contains("report");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void deserializeBlock_keepsTheNestedBlockName() {
        BlockDefinition nested = executor.deserializeBlock(
                Map.of("type", "simple", "name", "review", "config", Map.of("command", "check")));

        assertThat(nested.getType()).isEqualTo("simple");
        assertThat(nested.getName()).isEqualTo("review");
        assertThat(nested.getConfig()).containsEntry("command", "check");
    }

    @Test
    void execute_nestedNamedShellBlock_passesTheNameToTheStepBlock() {
        StepResult shellResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepAsShell(any(), any(), anyInt(), anyInt())).thenReturn(shellResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "shell", "name", "cleanup", "config", Map.of("command", "rm -rf tmp/"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        executor.execute(block, context);

        ArgumentCaptor<BlockDefinition> captor = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(any(), captor.capture(), anyInt(), anyInt());
        assertThat(captor.getValue().getName()).isEqualTo("cleanup");
    }
}
