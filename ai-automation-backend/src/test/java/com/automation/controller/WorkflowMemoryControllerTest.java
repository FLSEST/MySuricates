package com.automation.controller;

import com.automation.dto.request.ExtractMemoryRequest;
import com.automation.dto.request.UpdateMemoryRequest;
import com.automation.dto.response.WorkflowMemoryResponse;
import com.automation.entity.WorkflowMemory;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowMemoryMapper;
import com.automation.service.memory.WorkflowMemoryService;
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

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(WorkflowMemoryController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowMemoryControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private WorkflowMemoryService memoryService;
    @MockBean
    private ActivityLogService activityLogService;

    @MockBean private WorkflowMemoryMapper memoryMapper;
    @MockBean private ApiKeyService apiKeyService;

    private WorkflowMemoryResponse sampleResponse() {
        return new WorkflowMemoryResponse("m-1", "w-1", "Lesson learned", "Content",
                List.of("tag"), List.of("run-1"), "CLAUDE", true, Instant.now());
    }

    @Test
    void list_returnsMemories() throws Exception {
        when(memoryService.findByWorkflowId("w-1")).thenReturn(List.of());
        when(memoryMapper.toResponseList(any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/workflows/w-1/memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("m-1"))
                .andExpect(jsonPath("$[0].title").value("Lesson learned"));
    }

    @Test
    void extract_returns201() throws Exception {
        var memory = WorkflowMemory.builder().id("m-1").build();
        when(memoryService.extractKnowledge("w-1", List.of("run-1"), "CLAUDE")).thenReturn(memory);
        when(memoryMapper.toResponse(memory)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/workflows/w-1/memory/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExtractMemoryRequest(List.of("run-1"), "CLAUDE"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("m-1"));
    }

    @Test
    void extract_badRequest_returnsError() throws Exception {
        when(memoryService.extractKnowledge(any(), any(), any()))
                .thenThrow(new IllegalArgumentException("No run IDs"));

        mockMvc.perform(post("/api/v1/workflows/w-1/memory/extract")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new ExtractMemoryRequest(List.of(), null))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("No run IDs"));
    }

    @Test
    void update_returnsUpdated() throws Exception {
        var memory = WorkflowMemory.builder().id("m-1").build();
        when(memoryService.update("w-1", "m-1", "New title", "New content")).thenReturn(memory);
        when(memoryMapper.toResponse(memory)).thenReturn(sampleResponse());

        mockMvc.perform(put("/api/v1/workflows/w-1/memory/m-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new UpdateMemoryRequest("New title", "New content"))))
                .andExpect(status().isOk());
    }

    @Test
    void activate_returns204() throws Exception {
        mockMvc.perform(put("/api/v1/workflows/w-1/memory/m-1/activate"))
                .andExpect(status().isNoContent());
        verify(memoryService).activate("w-1", "m-1");
    }

    @Test
    void delete_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/workflows/w-1/memory/m-1"))
                .andExpect(status().isNoContent());
        verify(memoryService).delete("w-1", "m-1");
    }
}
