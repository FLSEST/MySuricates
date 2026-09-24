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
import com.automation.security.RateLimitConfig;
import com.automation.security.WebhookRateLimiter;
import com.automation.service.system.ApiKeyService;
import com.automation.service.webhook.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(WebhookController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private WebhookService webhookService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private WebhookMapper webhookMapper;

    @MockitoBean
    private WorkflowMapper workflowMapper;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private WebhookRateLimiter webhookRateLimiter;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

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
                "abc123def456ghi789jkl012mno345pq", null, false,
                true, "Test webhook", new HashMap<>(),
                testWebhook.getLastTriggeredAt(), 5, testWebhook.getCreatedAt()
        );

        when(webhookMapper.toResponse(any(WebhookTrigger.class))).thenReturn(testResponse);
        when(webhookRateLimiter.tryAcquire(any())).thenReturn(true);
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
    void listWebhooks_masksSecretTokenAndUrl() throws Exception {
        when(webhookService.findAll()).thenReturn(List.of(testWebhook));

        mockMvc.perform(get("/api/v1/webhooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].secretToken").value("abc123de…"))
                .andExpect(jsonPath("$[0].tokenVisible").value(false))
                .andExpect(jsonPath("$[0].webhookUrl", containsString("abc123de…")))
                .andExpect(jsonPath("$[0].webhookUrl",
                        not(containsString("abc123def456ghi789jkl012mno345pq"))));
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
    void getWebhook_masksSecretTokenAndUrl() throws Exception {
        when(webhookService.findById("wh-1")).thenReturn(testWebhook);

        mockMvc.perform(get("/api/v1/webhooks/wh-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretToken").value("abc123de…"))
                .andExpect(jsonPath("$.tokenVisible").value(false))
                .andExpect(jsonPath("$.webhookUrl",
                        not(containsString("abc123def456ghi789jkl012mno345pq"))));
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
    void createWebhook_revealsFullTokenAndSetsTokenVisible() throws Exception {
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
                .andExpect(jsonPath("$.secretToken").value("abc123def456ghi789jkl012mno345pq"))
                .andExpect(jsonPath("$.tokenVisible").value(true))
                .andExpect(jsonPath("$.webhookUrl", containsString("abc123def456ghi789jkl012mno345pq")));
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
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.tokenVisible").value(false))
                .andExpect(jsonPath("$.secretToken").value("abc123de…"))
                .andExpect(jsonPath("$.webhookUrl", not(containsString("abc123def456ghi789jkl012mno345pq"))));
    }

    @Test
    void getWebhook_withoutToken_doesNotRenderNullIntoUrl() throws Exception {
        testWebhook.setSecretToken(null);
        when(webhookService.findById("wh-1")).thenReturn(testWebhook);

        mockMvc.perform(get("/api/v1/webhooks/wh-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretToken").value(""))
                .andExpect(jsonPath("$.webhookUrl", not(containsString("null"))));
    }

    @Test
    void disableWebhook_returnsOk() throws Exception {
        testWebhook.setEnabled(false);
        when(webhookService.disable("wh-1")).thenReturn(testWebhook);

        WebhookTriggerResponse disabledResponse = new WebhookTriggerResponse(
                "wh-1", "wf-1", "Test Workflow",
                "abc123def456ghi789jkl012mno345pq", null, false,
                false, "Test webhook", new HashMap<>(),
                testWebhook.getLastTriggeredAt(), 5, testWebhook.getCreatedAt()
        );
        when(webhookMapper.toResponse(any(WebhookTrigger.class))).thenReturn(disabledResponse);

        mockMvc.perform(post("/api/v1/webhooks/wh-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.tokenVisible").value(false))
                .andExpect(jsonPath("$.secretToken").value("abc123de…"));
    }

    @Test
    void deleteWebhook_returns204() throws Exception {
        doNothing().when(webhookService).delete("wh-1");

        mockMvc.perform(delete("/api/v1/webhooks/wh-1"))
                .andExpect(status().isNoContent());

        verify(webhookService).delete("wh-1");
    }

    @Test
    void regenerateToken_returnsFullNewTokenAndTokenVisible() throws Exception {
        WebhookTrigger regenerated = WebhookTrigger.builder()
                .id("wh-1")
                .workflow(testWorkflow)
                .secretToken("zzz999NEWTOKENvalue1234567890ABCDEF")
                .enabled(true)
                .description("Test webhook")
                .defaultContext(new HashMap<>())
                .triggerCount(5)
                .lastTriggeredAt(testWebhook.getLastTriggeredAt())
                .createdAt(testWebhook.getCreatedAt())
                .build();
        when(webhookService.regenerateToken("wh-1")).thenReturn(regenerated);

        mockMvc.perform(post("/api/v1/webhooks/wh-1/regenerate-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secretToken").value("zzz999NEWTOKENvalue1234567890ABCDEF"))
                .andExpect(jsonPath("$.tokenVisible").value(true))
                .andExpect(jsonPath("$.secretToken",
                        not(equalTo("abc123def456ghi789jkl012mno345pq"))))
                .andExpect(jsonPath("$.webhookUrl", containsString("zzz999NEWTOKENvalue1234567890ABCDEF")));

        verify(webhookService).regenerateToken("wh-1");
    }

    @Test
    void triggerWebhook_validToken_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .status(RunStatus.RUNNING)
                .build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, null, RunStatus.RUNNING, List.of(),
                null, null, null, null, Map.of(), Map.of(), Map.of(), null
        );

        when(webhookService.trigger(eq("abc123token"), any(), any())).thenReturn(run);
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
        when(webhookService.trigger(eq("invalid"), any(), any()))
                .thenThrow(new ResourceNotFoundException("WebhookTrigger", "token"));

        mockMvc.perform(post("/api/v1/webhook/trigger/invalid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void triggerWebhook_disabledWebhook_returns409() throws Exception {
        when(webhookService.trigger(eq("disabled-token"), any(), any()))
                .thenThrow(new IllegalStateException("Webhook trigger is disabled"));

        mockMvc.perform(post("/api/v1/webhook/trigger/disabled-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isConflict());
    }

    @Test
    void triggerWebhook_passesTheRawBodyBytesUnchanged() throws Exception {
        WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
        when(webhookService.trigger(eq("abc123token"), any(), any())).thenReturn(run);
        String body = "{ \"action\" :  \"deploy\" }";

        mockMvc.perform(post("/api/v1/webhook/trigger/abc123token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Signature", "sha256=00")
                        .content(body))
                .andExpect(status().isOk());

        verify(webhookService).trigger(eq("abc123token"), org.mockito.AdditionalMatchers.aryEq(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                argThat((org.springframework.http.HttpHeaders h) -> "sha256=00".equals(h.getFirst("X-Signature"))));
    }

    @Test
    void triggerWebhook_invalidSignature_returns401() throws Exception {
        when(webhookService.trigger(eq("abc123token"), any(), any())).thenThrow(new com.automation.exception.HardeningException(
                com.automation.exception.ErrorCode.WEBHOOK_SIGNATURE_INVALID, "Missing or invalid X-Signature header"));

        mockMvc.perform(post("/api/v1/webhook/trigger/abc123token").content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("WEBHOOK_SIGNATURE_INVALID"));
    }

    @Test
    void generateSigningSecret_returnsTheSecretOnce() throws Exception {
        when(webhookService.generateSigningSecret("wh-1")).thenReturn("ab".repeat(32));

        mockMvc.perform(post("/api/v1/webhooks/wh-1/secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secret").value("ab".repeat(32)))
                .andExpect(jsonPath("$.header").value("X-Signature"));
    }

    @Test
    void removeSigningSecret_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/webhooks/wh-1/secret"))
                .andExpect(status().isNoContent());

        verify(webhookService).removeSigningSecret("wh-1");
    }

    @Test
    void responsesExposeWhetherASignatureIsRequired() throws Exception {
        WebhookTriggerResponse signed = new WebhookTriggerResponse("wh-1", "wf-1", "Test Workflow", "abc123def456ghi789jkl012mno345pq",
                null, false, true, "Test webhook", new HashMap<>(), null, 5, null, true);
        when(webhookMapper.toResponse(any(WebhookTrigger.class))).thenReturn(signed);
        when(webhookService.findById("wh-1")).thenReturn(testWebhook);

        mockMvc.perform(get("/api/v1/webhooks/wh-1"))
                .andExpect(jsonPath("$.signatureRequired").value(true));
    }

    @Test
    void triggerWebhook_noBody_returnsOk() throws Exception {
        WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
        WorkflowRunResponse runResponse = new WorkflowRunResponse(
                "run-1", "wf-1", "Test Workflow", null, null, RunStatus.RUNNING, List.of(),
                null, null, null, null, Map.of(), Map.of(), Map.of(), null
        );

        when(webhookService.trigger(eq("abc123token"), isNull(), any())).thenReturn(run);
        when(workflowMapper.toRunResponse(run)).thenReturn(runResponse);

        mockMvc.perform(post("/api/v1/webhook/trigger/abc123token"))
                .andExpect(status().isOk());
    }
}
