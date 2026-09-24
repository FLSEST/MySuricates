package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.factory.FactoryVerificationService;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkflowFactoryController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowFactoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowFactoryService factoryService;

    @MockitoBean
    private FactoryVerificationService verificationService;

    @MockitoBean
    private WorkflowMapper workflowMapper;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private WorkflowFactoryResult createFactoryResult() {
        return new WorkflowFactoryResult(
                "wf-1", "Generated Workflow", "VERIFIED",
                "run-1", "PASSED", null, false, 5000L, List.of());
    }

    @Test
    void generate_validRequest_returns201() throws Exception {
        Workflow workflow = Workflow.builder().id("wf-1").name("Generated").build();
        when(factoryService.generateWorkflow(any())).thenReturn(workflow);
        when(workflowMapper.toResponse(any())).thenReturn(createWorkflowResponse());

        mockMvc.perform(post("/api/v1/factory/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic": "CI/CD Pipeline", "projectId": "proj-1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("wf-1"));
    }

    @Test
    void generate_missingTopic_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/factory/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generate_blankTopic_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/factory/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic": "", "projectId": "proj-1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void generate_projectNotFound_returns404() throws Exception {
        when(factoryService.generateWorkflow(any()))
                .thenThrow(new ResourceNotFoundException("Project", "unknown"));

        mockMvc.perform(post("/api/v1/factory/generate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic": "Test", "projectId": "unknown"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void run_validRequest_returnsOk() throws Exception {
        when(factoryService.runFactory(any())).thenReturn(createFactoryResult());

        mockMvc.perform(post("/api/v1/factory/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"topic": "Test Pipeline"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.status").value("VERIFIED"));
    }

    @Test
    void run_missingTopic_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/factory/run")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void verify_existingId_returnsOk() throws Exception {
        when(verificationService.verifyWorkflow("wf-1")).thenReturn(createFactoryResult());

        mockMvc.perform(post("/api/v1/factory/verify/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.testStatus").value("PASSED"));
    }

    @Test
    void verify_nonExistentId_returns404() throws Exception {
        when(verificationService.verifyWorkflow("unknown"))
                .thenThrow(new ResourceNotFoundException("Workflow", "unknown"));

        mockMvc.perform(post("/api/v1/factory/verify/unknown"))
                .andExpect(status().isNotFound());
    }

    @Test
    void promote_existingId_returnsOk() throws Exception {
        WorkflowFactoryResult result = new WorkflowFactoryResult(
                "wf-1", "Promoted", "PROMOTED", null, null, null, true, 3000L, List.of());
        when(factoryService.promoteToTemplate("wf-1")).thenReturn(result);

        mockMvc.perform(post("/api/v1/factory/promote/wf-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.promotedAsTemplate").value(true));
    }

    @Test
    void getTopics_returnsOk() throws Exception {
        // Static catalog — no service stub needed
        mockMvc.perform(get("/api/v1/factory/topics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(8))
                .andExpect(jsonPath("$[0].id").value("CODE"))
                .andExpect(jsonPath("$[7].id").value("CUSTOM"));
    }

    private WorkflowResponse createWorkflowResponse() {
        return new WorkflowResponse(
                "wf-1", "proj-1", "Project", null, null,
                "Generated", null, null, List.of(), null, null, List.of(), List.of(),
                0, null, null, null, null, null, null, null, null, null, null);
    }
}
