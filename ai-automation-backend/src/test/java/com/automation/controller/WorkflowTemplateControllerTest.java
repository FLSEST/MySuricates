package com.automation.controller;

import com.automation.dto.response.WorkflowTemplateResponse;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowTemplateMapper;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.TemplateVerificationService;
import com.automation.service.workflow.WorkflowTemplateService;
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
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(WorkflowTemplateController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowTemplateControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private WorkflowTemplateService templateService;
    @MockBean
    private ActivityLogService activityLogService;

    @MockBean private WorkflowTemplateMapper templateMapper;
    @MockBean private TemplateVerificationService verificationService;
    @MockBean private ApiKeyService apiKeyService;

    private WorkflowTemplateResponse sampleResponse() {
        return new WorkflowTemplateResponse("t-1", Map.of("en", "Test"), Map.of("en", "Desc"),
                "icon", "devops", List.of(), null, null, false, 0, null,
                List.of(), null, Instant.now(), Instant.now());
    }

    @Test
    void listTemplates_returnsAll() throws Exception {
        when(templateService.findAll()).thenReturn(List.of());
        when(templateMapper.toResponseList(any())).thenReturn(List.of(sampleResponse()));
        when(verificationService.enrichAllWithVerifications(any())).thenReturn(List.of(sampleResponse()));

        mockMvc.perform(get("/api/v1/workflow-templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("t-1"));
    }

    @Test
    void getTemplate_returnsById() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.findById("t-1")).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());
        when(verificationService.enrichWithVerifications(any())).thenReturn(sampleResponse());

        mockMvc.perform(get("/api/v1/workflow-templates/t-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("t-1"));
    }

    @Test
    void createTemplate_returns201() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.create(any())).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());

        var request = Map.of("name", Map.of("en", "Test"), "blocks", List.of());

        mockMvc.perform(post("/api/v1/workflow-templates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated());
    }

    @Test
    void deleteTemplate_returns204() throws Exception {
        mockMvc.perform(delete("/api/v1/workflow-templates/t-1"))
                .andExpect(status().isNoContent());
        verify(templateService).delete("t-1");
    }

    @Test
    void createFromWorkflow_returns201() throws Exception {
        var template = WorkflowTemplate.builder().id("t-1").build();
        when(templateService.createFromWorkflow("w-1")).thenReturn(template);
        when(templateMapper.toResponse(template)).thenReturn(sampleResponse());

        mockMvc.perform(post("/api/v1/workflow-templates/from-workflow/w-1"))
                .andExpect(status().isCreated());
    }
}
