package com.automation.controller;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.AssistantService;
import com.automation.service.AssistantSessionService;
import com.automation.service.PageSuggestionService;
import org.springframework.context.ApplicationEventPublisher;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AssistantController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class AssistantControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AssistantService assistantService;
    @MockitoBean
    private AssistantSessionService assistantSessionService;
    @MockitoBean
    private PageSuggestionService pageSuggestionService;
    @MockitoBean
    private com.automation.service.assistant.AssistantChatBroadcaster broadcaster;
    @MockitoBean
    private ApplicationEventPublisher eventPublisher;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;
    @MockitoBean
    private com.automation.service.assistant.PendingToolRegistry pendingToolRegistry;
    @MockitoBean
    private com.automation.service.assistant.AssistantToolExecutor toolExecutor;

    @Test
    void chat_returnsAssistantResponse() throws Exception {
        var response = new AssistantChatResponse("Ich bin MySuricates.", List.of());
        when(assistantService.chat(any())).thenReturn(response);

        var request = new AssistantChatRequest("Hallo", List.of(), null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Ich bin MySuricates."))
                .andExpect(jsonPath("$.actions").isArray());

        verify(assistantService).chat(any());
    }

    @Test
    void chat_withActions_returnsActions() throws Exception {
        var action = new AssistantChatResponse.AssistantAction(
                "run_workflow", "wf-123", "Daily Report", "Jetzt starten");
        var response = new AssistantChatResponse("Starte den Daily Report?", List.of(action));
        when(assistantService.chat(any())).thenReturn(response);

        var request = new AssistantChatRequest("Starte Daily Report", List.of(), null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actions[0].type").value("run_workflow"))
                .andExpect(jsonPath("$.actions[0].id").value("wf-123"))
                .andExpect(jsonPath("$.actions[0].label").value("Jetzt starten"));
    }

    @Test
    void chat_withHistory_passesHistoryToService() throws Exception {
        var history = List.of(
                new AssistantChatRequest.MessageEntry("user", "Was lief heute?"),
                new AssistantChatRequest.MessageEntry("assistant", "Heute liefen 3 Workflows.")
        );
        when(assistantService.chat(any()))
                .thenReturn(new AssistantChatResponse("OK", List.of()));

        var request = new AssistantChatRequest("Und gestern?", history, null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(assistantService).chat(any(AssistantChatRequest.class));
    }

    /** TODO 2f7766e7: the old history form (role/content only) still arrives, with no tool results. */
    @Test
    void chat_legacyHistoryWithoutToolResults_arrivesWithNullToolResults() throws Exception {
        when(assistantService.chat(any())).thenReturn(new AssistantChatResponse("OK", List.of()));

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Weiter\",\"history\":[{\"role\":\"user\",\"content\":\"Leg an\"},"
                                + "{\"role\":\"assistant\",\"content\":\"Erledigt\"}]}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(AssistantChatRequest.class);
        verify(assistantService).chat(captor.capture());
        assertThat(captor.getValue().history()).extracting(AssistantChatRequest.MessageEntry::toolResults)
                .containsOnlyNulls();
    }

    /** TODO 2f7766e7: the new form carries the results of confirmed tools on the assistant entry. */
    @Test
    void chat_historyWithToolResults_passesThemToService() throws Exception {
        when(assistantService.chat(any())).thenReturn(new AssistantChatResponse("OK", List.of()));

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Behebe das\",\"history\":[{\"role\":\"assistant\",\"content\":\"Lege an\","
                                + "\"toolResults\":[{\"type\":\"create_workflow\",\"success\":true,"
                                + "\"message\":\"✓ Workflow created\"}]}]}"))
                .andExpect(status().isOk());

        var captor = org.mockito.ArgumentCaptor.forClass(AssistantChatRequest.class);
        verify(assistantService).chat(captor.capture());
        assertThat(captor.getValue().history().get(0).toolResults()).containsExactly(
                new AssistantChatRequest.ToolResultEntry("create_workflow", true, "✓ Workflow created"));
    }

    @Test
    void chat_withPageContext_passesContextToService() throws Exception {
        when(assistantService.chat(any()))
                .thenReturn(new AssistantChatResponse("Context received", List.of()));

        var ctx = new AssistantChatRequest.PageContext("run", "legacy-run-id", "proj-xyz", "wf-123", "run-abc", "/workflows/wf-123/run/run-abc");
        var request = new AssistantChatRequest("Was ist hier passiert?", List.of(), ctx, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Context received"));
    }

    @Test
    void chat_emptyMessage_returns400() throws Exception {
        var request = new AssistantChatRequest("", List.of(), null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    void chat_missingMessage_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"history\":[]}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chat_nullHistory_handledGracefully() throws Exception {
        when(assistantService.chat(any()))
                .thenReturn(new AssistantChatResponse("OK", List.of()));

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"Test\"}"))
                .andExpect(status().isOk());
    }

    // ── Pending-tool gate ──────────────────────────────────────────────────────

    @Test
    void confirmPendingTool_consumesRegistryAndExecutes() throws Exception {
        var tool = new com.automation.dto.PendingTool(
                "tool-1", "create_todo", "TODO anlegen: X", "{\"title\":\"X\"}",
                java.time.Instant.now().plusSeconds(60));
        when(pendingToolRegistry.take("tool-1")).thenReturn(java.util.Optional.of(tool));
        when(toolExecutor.executeConfirmed(tool, null))
                .thenReturn(new com.automation.service.assistant.ToolExecutionResult(
                        "tool-1", "create_todo", true, "✓ Todo created"));

        mockMvc.perform(post("/api/v1/assistant/pending-tools/tool-1/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolId").value("tool-1"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("✓ Todo created"));

        verify(toolExecutor).executeConfirmed(tool, null);
        verify(assistantSessionService).appendToolResult(
                org.mockito.ArgumentMatchers.eq("s1"),
                org.mockito.ArgumentMatchers.argThat(r -> r.success() && "tool-1".equals(r.toolId())));
    }

    /** TODO 81773d7b: the UI language from the confirm body reaches the tool (build check in English). */
    @Test
    void confirmPendingTool_passesUiLanguageToExecutor() throws Exception {
        var tool = new com.automation.dto.PendingTool(
                "tool-3", "create_workflow", "Workflow anlegen", "{}", java.time.Instant.now().plusSeconds(60));
        when(pendingToolRegistry.take("tool-3")).thenReturn(java.util.Optional.of(tool));
        when(toolExecutor.executeConfirmed(tool, "en"))
                .thenReturn(new com.automation.service.assistant.ToolExecutionResult(
                        "tool-3", "create_workflow", true, "✓ Workflow created"));

        mockMvc.perform(post("/api/v1/assistant/pending-tools/tool-3/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s1\",\"language\":\"en\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(toolExecutor).executeConfirmed(tool, "en");
    }

    @Test
    void confirmPendingTool_alreadyConsumed_returns410() throws Exception {
        when(pendingToolRegistry.take("gone")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/v1/assistant/pending-tools/gone/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone());

        verify(toolExecutor, org.mockito.Mockito.never()).executeConfirmed(any(), any());
    }

    @Test
    void rejectPendingTool_consumesWithoutExecuting() throws Exception {
        var tool = new com.automation.dto.PendingTool(
                "tool-2", "delete_workflow", "Workflow löschen", "{\"workflowId\":\"wf-9\"}",
                java.time.Instant.now().plusSeconds(60));
        when(pendingToolRegistry.take("tool-2")).thenReturn(java.util.Optional.of(tool));

        mockMvc.perform(post("/api/v1/assistant/pending-tools/tool-2/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"s2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolId").value("tool-2"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("✗ Abgelehnt"));

        verify(toolExecutor, org.mockito.Mockito.never()).executeConfirmed(any(), any());
        verify(assistantSessionService).appendToolResult(
                org.mockito.ArgumentMatchers.eq("s2"),
                org.mockito.ArgumentMatchers.argThat(r -> !r.success() && "tool-2".equals(r.toolId())));
    }

    // ── Session management ─────────────────────────────────────────────────────

    @Test
    void listSessions_returnsListFromService() throws Exception {
        var summary = new com.automation.dto.response.AssistantSessionSummary(
                "s1", "Hallo", "dashboard", 2, Instant.now());
        when(assistantSessionService.listRecentSessions()).thenReturn(List.of(summary));

        mockMvc.perform(get("/api/v1/assistant/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void listSessions_emptyList_returnsEmptyArray() throws Exception {
        when(assistantSessionService.listRecentSessions()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/assistant/sessions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void getSession_returnsDetail() throws Exception {
        var detail = new com.automation.dto.response.AssistantSessionDetail(
                "abc", "Test-Preview", "dashboard", null, List.of(), 0, Instant.now(), null);
        when(assistantSessionService.getSession("abc")).thenReturn(detail);

        mockMvc.perform(get("/api/v1/assistant/sessions/abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("abc"));

        verify(assistantSessionService).getSession("abc");
    }

    @Test
    void deleteSession_callsServiceAndReturns200() throws Exception {
        mockMvc.perform(delete("/api/v1/assistant/sessions/abc"))
                .andExpect(status().isOk());

        verify(assistantSessionService).deleteSession("abc");
    }

    // ── Chat session persistence ───────────────────────────────────────────────

    @Test
    void chat_persistsSessionWhenSessionIdProvided() throws Exception {
        when(assistantService.chat(any())).thenReturn(new AssistantChatResponse("OK", List.of()));

        // AssistantChatRequest(message, history, context, provider, sessionId, requestId)
        var request = new AssistantChatRequest("Hello", List.of(), null, null, "sess-1", null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(assistantSessionService).appendMessages(eq("sess-1"), any(), any());
    }

    @Test
    void chat_doesNotPersistWhenSessionIdNull() throws Exception {
        when(assistantService.chat(any())).thenReturn(new AssistantChatResponse("OK", List.of()));

        var request = new AssistantChatRequest("Hello", List.of(), null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(assistantSessionService, never()).appendMessages(any(), any(), any());
    }

    @Test
    void chat_cancelsRunningSuggestions() throws Exception {
        when(assistantService.chat(any())).thenReturn(new AssistantChatResponse("OK", List.of()));

        var request = new AssistantChatRequest("Hello", List.of(), null, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(pageSuggestionService).cancelRunning();
    }

    // ── Reject 410 ────────────────────────────────────────────────────────────

    @Test
    void rejectPendingTool_alreadyConsumed_returns410() throws Exception {
        when(pendingToolRegistry.take("xyz")).thenReturn(java.util.Optional.empty());

        mockMvc.perform(post("/api/v1/assistant/pending-tools/xyz/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isGone());

        verify(toolExecutor, never()).executeConfirmed(any(), any());
    }

    // ── Error propagation ─────────────────────────────────────────────────────

    @Test
    void getSession_serviceThrows_propagatesError() throws Exception {
        when(assistantSessionService.getSession("bad-id"))
                .thenThrow(new RuntimeException("Session not found"));

        mockMvc.perform(get("/api/v1/assistant/sessions/bad-id"))
                .andExpect(status().is5xxServerError());
    }
}
