package com.automation.service.integration;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.IntegrationBlockExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationBlockExecutorTest {

    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationOAuthService oAuthService;
    @Mock private IntegrationProvider provider;

    private IntegrationBlockExecutor executor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicInteger stepCounter = new AtomicInteger(0);

    @BeforeEach
    void setUp() {
        executor = new IntegrationBlockExecutor(registry, oAuthService, objectMapper);
    }

    @Test
    void supportsIntegrationType() {
        assertThat(executor.supports("integration")).isTrue();
        assertThat(executor.supports("shell")).isFalse();
        assertThat(executor.supports("simple")).isFalse();
    }

    @Test
    void executesActionSuccessfully() {
        BlockDefinition block = new BlockDefinition();
        block.setType("integration");
        block.setConfig(Map.of(
                "provider", "google-calendar",
                "action", "list_events",
                "params", Map.of("maxResults", 5)
        ));

        ExecutionContext context = createContext();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getValidAccessToken("google-calendar")).thenReturn("mock-token");
        when(provider.executeAction(eq("list_events"), any(), eq("mock-token")))
                .thenReturn(Map.of("items", List.of()));

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
        assertThat(result.getOutput()).contains("items");
    }

    @Test
    void failsWithMissingProvider() {
        BlockDefinition block = new BlockDefinition();
        block.setType("integration");
        block.setConfig(Map.of("action", "list_events"));

        StepResult result = executor.execute(block, createContext());
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("provider");
    }

    @Test
    void failsWithMissingAction() {
        BlockDefinition block = new BlockDefinition();
        block.setType("integration");
        block.setConfig(Map.of("provider", "google-calendar"));

        StepResult result = executor.execute(block, createContext());
        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("action");
    }

    @Test
    void handlesProviderError() {
        BlockDefinition block = new BlockDefinition();
        block.setType("integration");
        block.setConfig(Map.of("provider", "google-calendar", "action", "list_events"));

        ExecutionContext context = createContext();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getValidAccessToken("google-calendar")).thenReturn("mock-token");
        when(provider.executeAction(any(), any(), any()))
                .thenThrow(new IllegalStateException("API error 401"));

        StepResult result = executor.execute(block, context);

        assertThat(result.getStatus()).isEqualTo(StepStatus.FAILED);
        assertThat(result.getOutput()).contains("API error 401");
    }

    @Test
    void resolvesTemplateVariablesInParams() {
        BlockDefinition block = new BlockDefinition();
        block.setType("integration");
        block.setConfig(Map.of(
                "provider", "google-calendar",
                "action", "list_events",
                "params", Map.of("timeMin", "{{ started_at }}")
        ));

        ExecutionContext context = createContext();
        context.setVariable("started_at", "2026-04-07T00:00:00Z");

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(oAuthService.getValidAccessToken("google-calendar")).thenReturn("mock-token");
        when(provider.executeAction(eq("list_events"), any(), eq("mock-token")))
                .thenAnswer(inv -> {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> params = inv.getArgument(1);
                    assertThat(params.get("timeMin")).isEqualTo("2026-04-07T00:00:00Z");
                    return Map.of("items", List.of());
                });

        StepResult result = executor.execute(block, context);
        assertThat(result.getStatus()).isEqualTo(StepStatus.COMPLETED);
    }

    private ExecutionContext createContext() {
        return ExecutionContext.builder()
                .workflowRunId("test-run-1")
                .stepCounter(stepCounter)
                .build();
    }
}
