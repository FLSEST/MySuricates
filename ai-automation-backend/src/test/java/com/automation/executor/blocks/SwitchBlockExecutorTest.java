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
    void execute_nestedBlockWithoutType_isRejectedInsteadOfRunningAsAiCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("name", "typeless", "config", Map.of("command", "Analysiere das Repo"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("typeless");
        verifyNoInteractions(commandExecutor);
    }

    @Test
    void execute_nestedBlockWithBlankType_isRejectedInsteadOfRunningAsAiCall() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "  ", "config", Map.of("command", "Analysiere das Repo"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        verify(commandExecutor, never()).executeStepOnHost(any(HostExecutionRequest.class));
        verify(commandExecutor, never()).executeStepAsShell(any(), any(), anyInt(), anyInt());
    }

    @Test
    void execute_nestedNamedSimpleBlock_passesTheNameToTheHostRequest() {
        StepResult aiResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("reviewed").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(aiResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "review", "config", Map.of("command", "Pruefe das"))))));
        BlockDefinition block = BlockDefinition.builder().type("switch").config(config).build();

        executor.execute(block, context);

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor).executeStepOnHost(captor.capture());
        assertThat(captor.getValue().block().getName()).isEqualTo("review");
    }

    @Test
    void deserializeBlock_keepsTheNestedBlockName() {
        BlockDefinition nested = executor.deserializeBlock(
                Map.of("type", "simple", "name", "review", "config", Map.of("command", "check")));

        assertThat(nested.getType()).isEqualTo("simple");
        assertThat(nested.getName()).isEqualTo("review");
        assertThat(nested.getConfig()).containsEntry("command", "check");
    }

    /** 12a3b244: a name on a case block must also make its output addressable downstream. */
    @Test
    void execute_namedCaseBlock_outputIsAvailableUnderItsName() {
        StepResult aiResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("reviewed").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(aiResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "review", "config", Map.of("command", "Pruefe das"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.getBlockOutput("review")).isEqualTo("reviewed");
        assertThat(context.resolveTemplate("{{ block.review.output }}")).isEqualTo("reviewed");
    }

    /**
     * 2718c20e: a name that resolves as {{ block.&lt;name&gt;.output }} must also work as the
     * {@code source} of a compression block — with the case step's own facts in the header.
     */
    @Test
    void execute_namedCaseBlock_isRegisteredAsCompressionSource() {
        StepResult caseResult = StepResult.builder().stepIndex(1).status(StepStatus.FAILED)
                .output("kaputt").durationMs(2_000L).metadata(Map.of("blockType", "shell", "exitCode", 1)).build();
        when(commandExecutor.executeStepAsShell(any(), any(BlockDefinition.class), anyInt(), anyInt()))
                .thenReturn(caseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "shell", "name", "scan", "config", Map.of("command", "pruefe"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        var source = context.getSourceSteps().find("scan").orElseThrow();
        assertThat(source.blockName()).isEqualTo("scan");
        assertThat(source.status()).isEqualTo(StepStatus.FAILED);
        assertThat(source.exitCode()).isEqualTo(1);
        assertThat(source.blockType()).isEqualTo("shell");
    }

    /** End-to-end: switch case output → compress with the case name as source (2718c20e). */
    @Test
    void execute_namedCaseBlock_canBeCompressedByAFollowingCompressBlock() {
        StepResult caseResult = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                .output(("Zeile A\n").repeat(5) + "Zeile B\n").durationMs(1_000L).build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(caseResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "befund", "config", Map.of("command", "analysiere"))))));
        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        StepResult compressed = new CompressBlockExecutor().execute(
                BlockDefinition.builder().type("compress").name("befund_kompakt")
                        .config(Map.of("source", "befund", "stages", List.of(Map.of("op", "lines.dedupe", "count", true))))
                        .build(),
                context);

        assertThat(compressed.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(compressed.getOutput()).contains("## befund").contains("5× Zeile A").contains("Zeile B");
    }

    @Test
    void execute_namedBlocksInTheDefaultBranch_areRecordedTooAndKeepTheirOwnOutput() {
        StepResult first = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("collected").build();
        StepResult second = StepResult.builder()
                .stepIndex(2).status(StepStatus.COMPLETED).output("summarised").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(first, second);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "nothing-matches");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "config", Map.of("command", "skip"))))));
        config.put("default", List.of(
                Map.of("type", "simple", "name", "collect", "config", Map.of("command", "sammle")),
                Map.of("type", "simple", "name", "summarise", "config", Map.of("command", "fasse zusammen"))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.getBlockOutput("collect")).isEqualTo("collected");
        assertThat(context.getBlockOutput("summarise")).isEqualTo("summarised");
    }

    @Test
    void execute_unnamedCaseBlock_recordsNoNamedOutput() {
        StepResult aiResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("done").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(aiResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "config", Map.of("command", "arbeite"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.getBlockOutputs()).isEmpty();
    }

    /**
     * Case blocks run in sequence and each one moves {@code previous_output}, like the iterations
     * of a while loop — a second block in the same case sees the first one's output, not the step
     * before the switch (12a3b244).
     */
    @Test
    void execute_caseWithTwoBlocks_theSecondSeesTheFirstsOutputAsPreviousOutput() {
        context.setPreviousOutput("vor dem switch");
        StepResult first = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("erster").build();
        StepResult second = StepResult.builder()
                .stepIndex(2).status(StepStatus.COMPLETED).output("zweiter").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(first, second);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(
                Map.of("type", "simple", "config", Map.of("command", "Start: {{ previous_output }}")),
                Map.of("type", "simple", "config", Map.of("command", "Dann: {{ previous_output }}"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        ArgumentCaptor<HostExecutionRequest> captor = ArgumentCaptor.forClass(HostExecutionRequest.class);
        verify(commandExecutor, times(2)).executeStepOnHost(captor.capture());
        assertThat(captor.getAllValues().getFirst().block().getConfig())
                .containsEntry("command", "Start: vor dem switch");
        assertThat(captor.getAllValues().get(1).block().getConfig())
                .containsEntry("command", "Dann: erster");
    }

    /**
     * Block names are meant to be unique per workflow. When they are not, the last writer wins —
     * a case block overwrites the output an earlier top-level block stored under the same name.
     * The other direction (the switch's own aggregate overwriting its child) is pinned in
     * {@code BlockStepRunnerTest}.
     */
    @Test
    void execute_caseBlockNameCollidesWithAnEarlierBlock_theCaseBlockWins() {
        context.recordOutput("report", "alt");
        StepResult aiResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("neu").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(aiResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(
                Map.of("type", "simple", "name", "report", "config", Map.of("command", "schreibe"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.getBlockOutput("report")).isEqualTo("neu");
    }

    /** Taint is sticky: a case output in an untrusted run is shell-escaped downstream like any other. */
    @Test
    void execute_namedCaseBlockInUntrustedRun_outputIsRecordedAsTainted() {
        context.setUntrustedVariable("payload", "harmless");
        StepResult aiResult = StepResult.builder()
                .stepIndex(1).status(StepStatus.COMPLETED).output("a; id").build();
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(aiResult);

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks",
                List.of(Map.of("type", "simple", "name", "scan", "config", Map.of("command", "scanne"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.resolveShellTemplate("echo {{ block.scan.output }}", false))
                .isEqualTo("echo 'a; id'");
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

    /**
     * TODO 22e6bec8: the nested AI form counts too — platform data in a nested {@code simple}
     * block's prompt taints the output the switch records for that case.
     */
    @Test
    void execute_platformDataInANestedPrompt_taintsTheRecordedCaseOutput() {
        context.setUntrustedPlatformVariable("previous_report", "letzter Bericht");
        when(commandExecutor.executeStepOnHost(any(HostExecutionRequest.class))).thenReturn(
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("a | b").build());

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "A");
        config.put("cases", List.of(Map.of("value", "A", "blocks", List.of(Map.of(
                "type", "simple", "name", "analyse",
                "config", Map.of("command", "Bewerte: {{ previous_report }}"))))));

        executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(context.resolveShellTemplate("echo {{ block.analyse.output }}", false))
                .isEqualTo("echo 'a | b'");
    }

    /**
     * TODO bf11e6fe: an AI block typically ends its answer with a newline. The switch compares the
     * trimmed expression (the case values are trimmed already), so "GREEN\n" hits the case GREEN
     * instead of silently falling into default — while input and metadata keep the raw value.
     */
    @Test
    void execute_aiOutputWithTrailingNewline_hitsTheCaseAndKeepsTheRawValueVisible() {
        context.setBlockOutput("ampel", "GREEN\n");
        when(commandExecutor.executeStepAsShell(any(), any(BlockDefinition.class), anyInt(), anyInt())).thenReturn(
                StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output("gut").build());

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ block.ampel.output }}");
        config.put("cases", List.of(Map.of("value", "GREEN", "blocks",
                List.of(Map.of("type", "shell", "config", Map.of("command", "echo gut"))))));
        config.put("default", List.of(Map.of("type", "shell", "config", Map.of("command", "echo unbekannt"))));

        StepResult result = executor.execute(BlockDefinition.builder().type("switch").config(config).build(), context);

        assertThat(result.getMetadata()).containsEntry("matchedCase", "GREEN").containsEntry("expression", "GREEN\n");
        assertThat(result.getInput()).isEqualTo("switch (GREEN\n)");
        ArgumentCaptor<BlockDefinition> ran = ArgumentCaptor.forClass(BlockDefinition.class);
        verify(commandExecutor).executeStepAsShell(any(), ran.capture(), anyInt(), anyInt());
        assertThat(ran.getValue().getConfig()).containsEntry("command", "echo gut");
    }

    @Test
    void matchesValue_ignoresWhitespaceAroundTheExpressionButNotInsideIt() {
        assertThat(executor.matchesValue("GREEN\n", "GREEN")).isTrue();
        assertThat(executor.matchesValue("  critical \r\n", "error|critical")).isTrue();
        assertThat(executor.matchesValue("GREEN\nZwei Befunde", "GREEN")).isFalse();
        assertThat(executor.matchesValue(null, "GREEN")).isFalse();
    }
}
