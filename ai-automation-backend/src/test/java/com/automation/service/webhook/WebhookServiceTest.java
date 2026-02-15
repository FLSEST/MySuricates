package com.automation.service.webhook;

import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WebhookTriggerRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.workflow.WorkflowExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookServiceTest {

    @Mock
    private WebhookTriggerRepository webhookRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private WorkflowExecutor workflowExecutor;

    @InjectMocks
    private WebhookService webhookService;

    private Workflow testWorkflow;
    private WebhookTrigger testWebhook;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .build();

        testWebhook = WebhookTrigger.builder()
                .id("wh-1")
                .workflow(testWorkflow)
                .secretToken("abc123token")
                .enabled(true)
                .description("Test webhook")
                .defaultContext(new HashMap<>(Map.of("env", "test")))
                .triggerCount(0)
                .build();
    }

    @Test
    void findAll_returnsAll() {
        when(webhookRepository.findAll()).thenReturn(List.of(testWebhook));

        List<WebhookTrigger> result = webhookService.findAll();

        assertThat(result).hasSize(1);
    }

    @Test
    void findById_existing_returnsWebhook() {
        when(webhookRepository.findById("wh-1")).thenReturn(Optional.of(testWebhook));

        WebhookTrigger result = webhookService.findById("wh-1");

        assertThat(result.getSecretToken()).isEqualTo("abc123token");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(webhookRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void findByWorkflowId_returnsMatching() {
        when(webhookRepository.findByWorkflowId("wf-1")).thenReturn(List.of(testWebhook));

        List<WebhookTrigger> result = webhookService.findByWorkflowId("wf-1");

        assertThat(result).hasSize(1);
    }

    @Test
    void create_validWorkflow_createsWithToken() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(webhookRepository.save(any(WebhookTrigger.class))).thenAnswer(inv -> {
            WebhookTrigger wh = inv.getArgument(0);
            wh.setId("wh-new");
            return wh;
        });

        WebhookTrigger result = webhookService.create("wf-1", "My webhook", Map.of("key", "val"));

        assertThat(result.getId()).isEqualTo("wh-new");
        assertThat(result.getSecretToken()).isNotBlank().hasSizeGreaterThan(32); // Base64url-encoded 256-bit token
        assertThat(result.isEnabled()).isTrue();
        assertThat(result.getTriggerCount()).isEqualTo(0);
        assertThat(result.getDescription()).isEqualTo("My webhook");
        assertThat(result.getDefaultContext()).containsEntry("key", "val");
    }

    @Test
    void create_nonExistentWorkflow_throwsException() {
        when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.create("unknown", "desc", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_nullDefaultContext_defaultsToEmptyMap() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(webhookRepository.save(any(WebhookTrigger.class))).thenAnswer(inv -> inv.getArgument(0));

        WebhookTrigger result = webhookService.create("wf-1", "desc", null);

        assertThat(result.getDefaultContext()).isNotNull().isEmpty();
    }

    @Test
    void enable_setsEnabled() {
        testWebhook.setEnabled(false);
        when(webhookRepository.findById("wh-1")).thenReturn(Optional.of(testWebhook));
        when(webhookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookTrigger result = webhookService.enable("wh-1");

        assertThat(result.isEnabled()).isTrue();
    }

    @Test
    void disable_setsDisabled() {
        when(webhookRepository.findById("wh-1")).thenReturn(Optional.of(testWebhook));
        when(webhookRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        WebhookTrigger result = webhookService.disable("wh-1");

        assertThat(result.isEnabled()).isFalse();
    }

    @Test
    void delete_existingWebhook_deletes() {
        when(webhookRepository.findById("wh-1")).thenReturn(Optional.of(testWebhook));

        webhookService.delete("wh-1");

        verify(webhookRepository).delete(testWebhook);
    }

    // --- trigger ---

    @Test
    void trigger_enabledWebhook_executesWorkflowAndMergesContext() {
        when(webhookRepository.findBySecretToken("abc123token")).thenReturn(Optional.of(testWebhook));
        WorkflowRun expectedRun = WorkflowRun.builder().id("run-1").build();
        when(workflowExecutor.execute(any(), any())).thenReturn(expectedRun);

        Map<String, Object> payload = Map.of("action", "deploy");
        WorkflowRun result = webhookService.trigger("abc123token", payload);

        assertThat(result.getId()).isEqualTo("run-1");

        // Verify merged context: default ("env"="test") + payload ("action"="deploy")
        verify(workflowExecutor).execute(eq(testWorkflow), argThat(ctx ->
                ctx.containsKey("env") && ctx.containsKey("action")
        ));

        // Verify atomic increment was called
        verify(webhookRepository).incrementTriggerCount(eq("wh-1"), any(Instant.class));
    }

    @Test
    void trigger_payloadOverridesDefaultContext() {
        testWebhook.setDefaultContext(new HashMap<>(Map.of("env", "test", "debug", "false")));
        when(webhookRepository.findBySecretToken("abc123token")).thenReturn(Optional.of(testWebhook));
        when(workflowExecutor.execute(any(), any())).thenReturn(WorkflowRun.builder().id("run-1").build());

        webhookService.trigger("abc123token", Map.of("env", "prod"));

        verify(workflowExecutor).execute(eq(testWorkflow), argThat(ctx ->
                "prod".equals(ctx.get("env")) && "false".equals(ctx.get("debug"))
        ));
    }

    @Test
    void trigger_nullPayload_usesDefaultContextOnly() {
        when(webhookRepository.findBySecretToken("abc123token")).thenReturn(Optional.of(testWebhook));
        when(workflowExecutor.execute(any(), any())).thenReturn(WorkflowRun.builder().id("run-1").build());

        webhookService.trigger("abc123token", null);

        verify(workflowExecutor).execute(eq(testWorkflow), argThat(ctx ->
                ctx.containsKey("env") && ctx.size() == 1
        ));
    }

    @Test
    void trigger_disabledWebhook_throwsException() {
        testWebhook.setEnabled(false);
        when(webhookRepository.findBySecretToken("abc123token")).thenReturn(Optional.of(testWebhook));

        assertThatThrownBy(() -> webhookService.trigger("abc123token", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disabled");

        verify(workflowExecutor, never()).execute(any(), any());
    }

    @Test
    void trigger_invalidToken_throwsException() {
        when(webhookRepository.findBySecretToken("invalid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> webhookService.trigger("invalid", null))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
