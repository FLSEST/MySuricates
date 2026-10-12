package com.automation.controller;

import com.automation.dto.request.CreateTodoTriggerRequest;
import com.automation.dto.response.TodoTriggerResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoTriggerMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.trigger.TodoTriggerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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

@WebMvcTest(TodoTriggerController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TodoTriggerControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private TodoTriggerService service;
    @MockitoBean private TodoTriggerMapper mapper;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private static final Instant NOW = Instant.parse("2026-03-28T12:00:00Z");

    private TodoTriggerResponse sampleResponse(String id, boolean enabled) {
        return new TodoTriggerResponse(
                id, "bug", "proj-1", "AI", "HIGH",
                "wf-1", "Target Workflow",
                Map.of("key", "value"), enabled, 3, true, false,
                "Test trigger", null, 0, NOW);
    }

    @Test
    void listAll_returnsOk() throws Exception {
        when(service.findAll()).thenReturn(List.of());
        when(mapper.toResponseList(any())).thenReturn(List.of(sampleResponse("tt-1", true)));

        mockMvc.perform(get("/api/v1/todo-triggers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tt-1"))
                .andExpect(jsonPath("$[0].tagPattern").value("bug"))
                .andExpect(jsonPath("$[0].targetWorkflowName").value("Target Workflow"));
    }

    @Test
    void getById_existingId_returnsOk() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("tt-1", true));

        mockMvc.perform(get("/api/v1/todo-triggers/tt-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("tt-1"))
                .andExpect(jsonPath("$.enabled").value(true));
    }

    @Test
    void getById_nonExistent_returns404() throws Exception {
        when(service.findById("unknown")).thenThrow(new ResourceNotFoundException("TodoTrigger", "unknown"));

        mockMvc.perform(get("/api/v1/todo-triggers/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getByTargetWorkflow_returnsOk() throws Exception {
        when(service.findByTargetWorkflowId("wf-1")).thenReturn(List.of());
        when(mapper.toResponseList(any())).thenReturn(List.of(sampleResponse("tt-1", true)));

        mockMvc.perform(get("/api/v1/todo-triggers/workflow/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("tt-1"));
    }

    @Test
    void create_validRequest_returns201() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("tt-new", true));

        var request = new CreateTodoTriggerRequest(
                "bug", "proj-1", "AI", "HIGH", "wf-1",
                Map.of("key", "value"), "New trigger", 3, true, false);

        mockMvc.perform(post("/api/v1/todo-triggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("tt-new"));
    }

    @Test
    void create_missingTagPattern_returns400() throws Exception {
        String body = """
                {
                    "tagPattern": "",
                    "targetWorkflowId": "wf-1"
                }
                """;

        mockMvc.perform(post("/api/v1/todo-triggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void create_missingTargetWorkflowId_returns400() throws Exception {
        String body = """
                {
                    "tagPattern": "bug",
                    "targetWorkflowId": ""
                }
                """;

        mockMvc.perform(post("/api/v1/todo-triggers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void enable_returnsOk() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("tt-1", true));

        mockMvc.perform(post("/api/v1/todo-triggers/tt-1/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true));

        verify(service).enable("tt-1");
    }

    @Test
    void disable_returnsOk() throws Exception {
        when(mapper.toResponse(any())).thenReturn(sampleResponse("tt-1", false));

        mockMvc.perform(post("/api/v1/todo-triggers/tt-1/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        verify(service).disable("tt-1");
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/todo-triggers/tt-1"))
                .andExpect(status().isNoContent());

        verify(service).delete("tt-1");
    }
}
