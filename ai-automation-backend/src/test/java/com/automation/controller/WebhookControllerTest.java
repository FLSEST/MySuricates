package com.automation.controller;

import com.automation.dto.response.WebhookTriggerResponse;
import com.automation.dto.response.WorkflowRunResponse;
import com.automation.entity.RunStatus;
import com.automation.entity.WebhookTrigger;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WebhookMapper;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.system.ApiKeyService;
import com.automation.service.webhook.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WebhookController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WebhookService webhookService;

    @MockBean
    private WebhookMapper webhookMapper;

    @MockBean
    private WorkflowMapper workflowMapper;

    @MockBean
    private ApiKeyService apiKeyService;

    private Workflow testWorkflow;
    private WebhookTrigger testWebhook;
    private WebhookTriggerResponse testResponse;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder()
                .id("wf-1")
                .name("Test Workflow")
                .build();

        testWebhook = WebhookTrigger.builder()
                .id("wh-1")
                .workflow(testWorkflow)
                .secretToken("abc123def456ghi789jkl012mno345pq")
                .enabled(true)
                .description("Test webhook")
                .defaultContext(new HashMap<>())
                .triggerCount(5)
                .lastTriggeredAt(Instant.now())
                .createdAt(Instant.now())
                .build();

        testResponse = new WebhookTriggerResponse(
                "wh-1", "wf-1", "Test Workflow",
                "abc123def456ghi789jkl012mno345pq", null,
                true, "Test webhook", new HashMap<>(),
                testWebhook.getLastTriggeredAt(), 5, testWebhook.getCreatedAt()
        );

        when(webhookMapper.toResponse(any(WebhookTrigger.class))).thenReturn(testResponse);
    }

    @Test
    void listWebhooks_returnsOk() throws Exception {
        when(webhookService.findAll()).thenReturn(List.of(testWebhook));

        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("wh-1"))
                .andExpect(jsonPath("$[0].enabled").value(true));
    }

    @Test
    void getWebhook_existingId_returnsOk() throws Exception {
        when(webhookService.findById("wh-1")).thenReturn(testWebhook);

        mockMvc.perform(get("/api/v1/webhooks/wh-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wh-1"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"));
    }

    @Test
    void getWebhook_nonExistent_returns404() throws Exception {
        when(webhookService.findById("unknown"))
                .thenThrow(new ResourceNotFoundException("WebhookTrigger", "unknown"));

        mockMvc.perform(get("/api/v1/webhooks/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByWorkflow_returnsOk() throws Exception {
        when(webhookService.findByWorkflowId("wf-1")).thenReturn(List.of(testWebhook));

        mockMvc.perform(get("/api/v1/webhooks/workflow/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId").value("wf-1"));
    }

    @Test
    void createWebhook_validRequest_returns201() throws Exception {
        when(webhookService.create(any(), any(), any())).thenReturn(testWebhook);

        String body = """
                {
                    "workflowId": "wf-1",
                    "description": "My webhook"
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("wh-1"));
    }

    @Test
    void createWebhook_missingWorkflowId_returns400() throws Exception {
        String body = """
                {
                    "description": "Missing workflow"
                }
                """;

        mockMvc.perform(post("/api/v1/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enableWebhook_returnsOk() throws Exception {
        testWebhook.setEnabled(true);
        when(webhookService.enable("wh-1")).thenReturn(testWebhook);

        mockMvc.perform(post("/api/v1/webhooks/wh-1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void disableWebhook_returnsOk() throws Exception {
        testWebhook.setEnabled(false);
        when(webhookService.disable("wh-1")).thenReturn(testWebhook);

        WebhookTriggerResponse disabledResponse = new WebhookTriggerResponse(
                "wh-1", "wf-1", "Test Workflow",
                "abc123def456ghi789jkl012mno345pq", null,
                false, "Test webhook", new HashMap<>(),
                testWebhook.getLastTriggeredAt(), 5, testWebhook.getCreatedAt()
        );
        when(webhookMapper.toResponse(any(WebhookTrigger.class))).thenReturn(disabledResponse);

        mockMvc.perform(post("/api/v1/webhooks/wh-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));
    }

    @Test
    void deleteWebhook_returns204() throws Exception {
        doNothing().when(webhookService).delete("wh-1");

        mockMvc.perform(delete("/api/v1/webhooks/wh-1"))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("wh-1");
    }

    @Test
    void triggerWebhook_validToken_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .status(RunStatus.RUNNING)
                .build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, RunStatus.RUNNING, List.of(),
                null, null, null, null, Map.of(), Map.of(), Map.of()
        );

        when(webhookService.trigger(eq("abc123token"), any())).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        String body = """
                {"action": "deploy"}
                """;

        mockMvc.perform(post("/api/v1/webhook/trigger/abc123token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("run-1"))
                .andExpect(jsonPath("$.status").value("RUNNING"));
    }

    @Test
    void triggerWebhook_invalidToken_returns404() throws Exception {
        when(webhookService.trigger(eq("invalid"), any()))
                .thenThrow(new ResourceNotFoundException("WebhookTrigger", "token"));

        mockMvc.perform(post("/api/v1/webhook/trigger/invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerWebhook_disabledWebhook_returns409() throws Exception {
        when(webhookService.trigger(eq("disabled-token"), any()))
                .thenThrow(new IllegalStateException("Webhook trigger is disabled"));

        mockMvc.perform(post("/api/v1/webhook/trigger/disabled-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void triggerWebhook_noBody_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, RunStatus.RUNNING, List.of(),
                null, null, null, null, Map.of(), Map.of(), Map.of()
        );

        when(webhookService.trigger(eq("abc123token"), isNull())).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        mockMvc.perform(post("/api/v1/webhook/trigger/abc123token"))
                .andExpect(status().isOk());
    }
}
