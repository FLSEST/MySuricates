package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepStatus;
import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.CommandExecutorService;
import com.automation.service.workflow.ExecutionBroadcaster;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HumanInputBlockExecutorTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private CommandExecutorService commandExecutor;

    private HumanInputBlockExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HumanInputBlockExecutor(new ExecutionBroadcaster(messagingTemplate),
                new StepActors(commandExecutor, ModuleRegistry.allEnabled()));
    }

    @Test
    void supports_humanInput_returnsTrue() {
        assertThat(executor.supports("human-input")).isTrue();
    }

    @Test
    void supports_otherType_returnsFalse() {
        assertThat(executor.supports("simple")).isFalse();
        assertThat(executor.supports("shell")).isFalse();
    }

    @Test
    void execute_textInput_completesWithAnswer() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "What is your name?");
        config.put("input_type", "text");
        config.put("variable_name", "user_name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContext();

        // Pre-complete the future before execute blocks on it
        CompletableFuture<String> future = new CompletableFuture<>();
        future.complete("Alice");

        // We need to intercept requestApproval to return our pre-completed future
        var spyContext = buildContextWithPrecompletedApproval("Alice");

        var result = executor.execute(block, spyContext);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("Alice");
        assertThat(result.getInput()).contains("What is your name?");
        assertThat(spyContext.getVariable("user_name")).isEqualTo("Alice");
    }

    @Test
    void execute_selectInput_completesWithSelection() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Choose environment");
        config.put("input_type", "select");
        config.put("variable_name", "env");
        config.put("options", List.of("dev", "staging", "prod"));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("staging");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("staging");
        assertThat(context.getVariable("env")).isEqualTo("staging");
    }

    @Test
    void execute_timeout_withDefaultValue_usesDefault() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "fallback");
        config.put("timeout_minutes", 0); // 0 minutes = immediate timeout
        config.put("use_default_on_timeout", true);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        // Use context where approval never completes
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo("fallback");
        assertThat(context.getVariable("val")).isEqualTo("fallback");
    }

    @Test
    void execute_timeout_withoutDefault_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "");
        config.put("timeout_minutes", 0);
        config.put("use_default_on_timeout", true);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("Timed out");
    }

    @Test
    void execute_timeout_useDefaultDisabled_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");
        config.put("default_value", "fallback");
        config.put("timeout_minutes", 0);
        config.put("use_default_on_timeout", false);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_broadcastsWebSocketEvents() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter name");
        config.put("variable_name", "name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("Bob");

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(2)).convertAndSend(
                eq("/topic/executions/run-1/logs"), (Object) captor.capture());

        var payloads = captor.getAllValues();
        // First: human_input_required
        assertThat(payloads.get(0).get("type")).isEqualTo("human_input_required");
        assertThat(payloads.get(0).get("prompt")).isEqualTo("Enter name");
        // Second: human_input_resolved
        assertThat(payloads.get(1).get("type")).isEqualTo("human_input_resolved");
        assertThat(payloads.get(1).get("answer")).isEqualTo("Bob");
    }

    @Test
    @SuppressWarnings("unchecked")
    void execute_broadcastsTheRequestAndTheAnswer_withEveryField() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Which file?");
        config.put("input_type", "select");
        config.put("variable_name", "file");
        config.put("options", List.of("a.md", "b.md"));
        config.put("default_value", "a.md");
        config.put("required", false);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("b.md");

        var result = executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/executions/run-1/logs"), (Object) captor.capture());
        int stepIndex = result.getStepIndex();
        // Sent through ExecutionBroadcaster.broadcastLogMessage, like every other run log event
        assertThat(captor.getAllValues()).allSatisfy(payload ->
                assertThat(payload).containsEntry("runId", "run-1").containsKey("timestamp"));
        assertThat(captor.getAllValues().get(0))
                .containsEntry("type", "human_input_required")
                .containsEntry("blockId", "human-input-" + stepIndex)
                .containsEntry("prompt", "Which file?")
                .containsEntry("inputType", "select")
                .containsEntry("options", List.of("a.md", "b.md"))
                .containsEntry("defaultValue", "a.md")
                .containsEntry("required", false)
                .containsEntry("validationPattern", null)
                .containsEntry("variableName", "file")
                .containsEntry("stepIndex", stepIndex)
                .doesNotContainKey("fields");
        assertThat(captor.getAllValues().get(1))
                .containsEntry("type", "human_input_resolved")
                .containsEntry("stepIndex", stepIndex)
                .containsEntry("answer", "b.md");
    }

    @Test
    void execute_timeoutWithDefault_broadcastsTheDefaultAsTheAnswer() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Name?");
        config.put("default_value", "anon");
        config.put("timeout_minutes", 0);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithNeverCompletingApproval();

        executor.execute(block, context);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, times(2)).convertAndSend(eq("/topic/executions/run-1/logs"), (Object) captor.capture());
        assertThat(captor.getAllValues().get(1))
                .containsEntry("type", "human_input_resolved")
                .containsEntry("answer", "anon");
    }

    @Test
    void execute_defaultConfig_usesDefaults() {
        // Empty config — all defaults should apply
        var config = new HashMap<String, Object>();

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("answer");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getInput()).contains("Please provide input:");
        assertThat(context.getVariable("user_input")).isEqualTo("answer");
    }

    @Test
    void execute_cancelledFuture_fails() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Enter value");
        config.put("variable_name", "val");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithCancelledApproval();

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("cancelled");
    }

    // --- Multi-field tests ---

    @Test
    void execute_multiField_storesEachFieldAsContextVariable() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Bitte mehrere Werte:");
        config.put("fields", List.of(
                Map.of("variable_name", "first", "input_type", "text"),
                Map.of("variable_name", "second", "input_type", "text")
        ));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        String json = "{\"first\":\"A\",\"second\":\"B\"}";
        var context = buildContextWithPrecompletedApproval(json);

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).isEqualTo(json);
        assertThat(context.getVariable("first")).isEqualTo("A");
        assertThat(context.getVariable("second")).isEqualTo("B");
    }

    @Test
    void execute_multiField_missingValueDefaultsToEmpty() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Bitte:");
        config.put("fields", List.of(
                Map.of("variable_name", "name"),
                Map.of("variable_name", "age")
        ));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("{\"name\":\"Alex\"}");

        executor.execute(block, context);

        assertThat(context.getVariable("name")).isEqualTo("Alex");
        assertThat(context.getVariable("age")).isEqualTo("");
    }

    @Test
    void execute_multiField_invalidJson_fallsBackToSingleVariable() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Bitte:");
        config.put("variable_name", "fallback");
        config.put("fields", List.of(Map.of("variable_name", "x")));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("not-json");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("fallback")).isEqualTo("not-json");
    }

    @Test
    void execute_multiField_broadcastIncludesFields() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Bitte:");
        config.put("fields", List.of(Map.of("variable_name", "x", "input_type", "text")));

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("{\"x\":\"y\"}");

        executor.execute(block, context);

        ArgumentCaptor<Map<String, Object>> payloadCaptor = ArgumentCaptor.forClass(Map.class);
        verify(messagingTemplate, atLeastOnce()).convertAndSend(any(String.class), (Object) payloadCaptor.capture());
        var firstPayload = payloadCaptor.getAllValues().get(0);
        assertThat(firstPayload).containsEntry("type", "human_input_required");
        assertThat(firstPayload).containsKey("fields");
    }

    @Test
    void execute_singleField_legacyBehavior_unchanged() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Name?");
        config.put("variable_name", "name");

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("Alex");

        var result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(context.getVariable("name")).isEqualTo("Alex");
        assertThat(result.getOutput()).isEqualTo("Alex");
    }

    // --- Helpers ---

    @Test
    void execute_promptAndDefaultPlaceholders_areResolved() {
        var config = new HashMap<String, Object>();
        config.put("prompt", "Befund: {{ block.scan.output }}. Welche Datei?");
        config.put("default_value", "{{ block.scan.output }}.md");
        config.put("timeout_minutes", 15);

        var block = BlockDefinition.builder().type("human-input").config(config).build();
        var context = buildContextWithPrecompletedApproval("ok");
        context.recordOutput("scan", "bericht");

        executor.execute(block, context);

        var captor = ArgumentCaptor.forClass(ApprovalRequest.class);
        verify(context).requestApproval(captor.capture());
        assertThat(captor.getValue().getQuestion()).isEqualTo("Befund: bericht. Welche Datei?");
        assertThat(captor.getValue().getDefaultValue()).isEqualTo("bericht.md");
        assertThat(captor.getValue().getTimeoutMinutes()).isEqualTo(15);
    }

    private ExecutionContext buildContext() {
        return ExecutionContext.builder()
                .containerId("container-1")
                .workflowRunId("run-1")
                .build();
    }

    private ExecutionContext buildContextWithPrecompletedApproval(String answer) {
        ExecutionContext context = spy(buildContext());
        doAnswer(inv -> {
            CompletableFuture<String> future = (CompletableFuture<String>) inv.callRealMethod();
            future.complete(answer);
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private ExecutionContext buildContextWithNeverCompletingApproval() {
        ExecutionContext context = spy(buildContext());
        doReturn(new CompletableFuture<String>()).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private ExecutionContext buildContextWithCancelledApproval() {
        ExecutionContext context = spy(buildContext());
        doAnswer(inv -> {
            CompletableFuture<String> future = new CompletableFuture<>();
            future.completeExceptionally(new RuntimeException("cancelled by user"));
            return future;
        }).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }
}
