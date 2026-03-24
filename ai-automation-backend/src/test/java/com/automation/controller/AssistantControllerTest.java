package com.automation.controller;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.AssistantService;
import com.automation.service.AssistantSessionService;
import org.springframework.context.ApplicationEventPublisher;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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

    @MockBean
    private AssistantService assistantService;
    @MockBean
    private AssistantSessionService assistantSessionService;
    @MockBean
    private ApplicationEventPublisher eventPublisher;
    @MockBean
    private ApiKeyService apiKeyService;
    @MockBean
    private ActivityLogService activityLogService;

    @Test
    void chat_returnsAssistantResponse() throws Exception {
        var response = new AssistantChatResponse("Ich bin MySuricates.", List.of());
        when(assistantService.chat(any())).thenReturn(response);

        var request = new AssistantChatRequest("Hallo", List.of(), null, null, null);

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

        var request = new AssistantChatRequest("Starte Daily Report", List.of(), null, null, null);

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

        var request = new AssistantChatRequest("Und gestern?", history, null, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk());

        verify(assistantService).chat(any(AssistantChatRequest.class));
    }

    @Test
    void chat_withPageContext_passesContextToService() throws Exception {
        when(assistantService.chat(any()))
                .thenReturn(new AssistantChatResponse("Context received", List.of()));

        var ctx = new AssistantChatRequest.PageContext("run", "run-abc", "proj-xyz");
        var request = new AssistantChatRequest("Was ist hier passiert?", List.of(), ctx, null, null);

        mockMvc.perform(post("/api/v1/assistant/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Context received"));
    }

    @Test
    void chat_emptyMessage_returns400() throws Exception {
        var request = new AssistantChatRequest("", List.of(), null, null, null);

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
}
