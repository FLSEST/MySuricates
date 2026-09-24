package com.automation.controller;

import com.automation.dto.request.CreateCompletionTriggerRequest;
import com.automation.dto.response.CompletionTriggerResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.CompletionTriggerMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.trigger.CompletionTriggerService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(CompletionTriggerController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class CompletionTriggerControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private CompletionTriggerService service;
    @MockitoBean private CompletionTriggerMapper mapper;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private static final Instant NOW = Instant.parse("2026-03-28T12:00:00Z");

    private CompletionTriggerResponse sampleResponse(String id) {
        return new CompletionTriggerResponse(
                id, "src-wf", "Source Workflow", "tgt-wf", "Target Workflow",
                true, true, false, false, "Test trigger",
                Map.of("key", "value"), null, 0, NOW);
    }

    @Test
    void listAll_returnsOk() throws Exception {
        when(service.findAll()).thenReturn(List.of());
        when(mapper.toResponseList(any())).thenReturn(List.of(sampleResponse("t-1")));

        mockMvc.perform(get("/api/v1/completion-triggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"))
                .andExpect(jsonPath("$[0].sourceWorkflowName").value("Source Workflow"));
    }

    @Test
    void getById_existingId_returnsOk() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("t-1"));

        mockMvc.perform(get("/api/v1/completion-triggers/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-1"));
    }

    @Test
    void getById_nonExistent_returns404() throws Exception {
        when(service.findById("unknown")).thenThrow(new ResourceNotFoundException("CompletionTrigger", "unknown"));

        mockMvc.perform(get("/api/v1/completion-triggers/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getBySourceWorkflow_returnsOk() throws Exception {
        when(service.findBySourceWorkflowId("src-wf")).thenReturn(List.of());
        when(mapper.toResponseList(any())).thenReturn(List.of(sampleResponse("t-1")));

        mockMvc.perform(get("/api/v1/completion-triggers/workflow/src-wf/as-source"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"));
    }

    @Test
    void getByTargetWorkflow_returnsOk() throws Exception {
        when(service.findByTargetWorkflowId("tgt-wf")).thenReturn(List.of());
        when(mapper.toResponseList(any())).thenReturn(List.of(sampleResponse("t-1")));

        mockMvc.perform(get("/api/v1/completion-triggers/workflow/tgt-wf/as-target"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("t-new"));

        var request = new CreateCompletionTriggerRequest(
                "src-wf", "tgt-wf", true, false, false, "New trigger", Map.of());

        mockMvc.perform(post("/api/v1/completion-triggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("t-new"));
    }

    @Test
    void create_missingSourceWorkflowId_returns400() throws Exception {
        String body = """
                {
                    "sourceWorkflowId": "",
                    "targetWorkflowId": "tgt-wf",
                    "triggerOnSuccess": true
                }
                """;

        mockMvc.perform(post("/api/v1/completion-triggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enable_returnsOk() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("t-1"));

        mockMvc.perform(post("/api/v1/completion-triggers/t-1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).enable("t-1");
    }

    @Test
    void disable_returnsOk() throws Exception {
        var disabled = new CompletionTriggerResponse(
                "t-1", "src-wf", "Source", "tgt-wf", "Target",
                false, true, false, false, null, Map.of(), null, 0, NOW);
        when(mapper.toResponse(any())).thenReturn(disabled);

        mockMvc.perform(post("/api/v1/completion-triggers/t-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(service).disable("t-1");
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/completion-triggers/t-1"))
                .andExpect(status().isNoContent());

        verify(service).delete("t-1");
    }
}
