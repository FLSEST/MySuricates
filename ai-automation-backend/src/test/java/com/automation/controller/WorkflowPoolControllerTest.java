package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.request.InstallPoolWorkflowRequest;
import com.automation.dto.response.ExportToPoolResponse;
import com.automation.dto.response.PoolWorkflowEntry;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.pool.WorkflowPoolService;
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

import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(WorkflowPoolController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowPoolControllerTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private WorkflowPoolService poolService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    private PoolWorkflowEntry sampleEntry() {
        return new PoolWorkflowEntry(
                "code-review", Map.of("en", "Code Review", "de", "Code Review"),
                Map.of("en", "Reviews code"), "magnifying_glass", "development", "portable",
                List.of("code", "review"), List.of(), "Review the code", "claude-sonnet-4-6",
                "1.0.0", "system", null, null, "code-review.json"
        );
    }

    @Test
    void listAll_returnsOk() throws Exception {
        when(poolService.listAll(null)).thenReturn(List.of(sampleEntry()));

        mockMvc.perform(get("/api/v1/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("code-review"))
                .andExpect(jsonPath("$[0].name.en").value("Code Review"))
                .andExpect(jsonPath("$[0].category").value("development"));
    }

    @Test
    void listAll_emptyPool_returnsEmptyList() throws Exception {
        when(poolService.listAll(null)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void listAll_passesTheLocaleForBilingualSteps() throws Exception {
        when(poolService.listAll("de")).thenReturn(List.of(sampleEntry()));

        mockMvc.perform(get("/api/v1/pool").param("locale", "de"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void getById_existing_returnsOk() throws Exception {
        when(poolService.findById("code-review", null)).thenReturn(sampleEntry());

        mockMvc.perform(get("/api/v1/pool/code-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("code-review"))
                .andExpect(jsonPath("$.version").value("1.0.0"));
    }

    @Test
    void getById_notFound_returns404() throws Exception {
        when(poolService.findById("unknown", null))
                .thenThrow(new ResourceNotFoundException("PoolWorkflow", "unknown"));

        mockMvc.perform(get("/api/v1/pool/unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void install_validRequest_returnsOk() throws Exception {
        Workflow wf = Workflow.builder().id("wf-1").name("Code Review").build();
        when(poolService.install(eq("code-review"), any(InstallPoolWorkflowRequest.class)))
                .thenReturn(new WorkflowPoolService.InstallResult(wf, true));

        mockMvc.perform(post("/api/v1/pool/code-review/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1", "name": "My Review", "forceCreate": true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.workflowName").value("Code Review"))
                .andExpect(jsonPath("$.created").value(true));
    }

    @Test
    void install_missingProjectId_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/pool/code-review/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "", "name": "test"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exportToPool_passesScopeQueryParamThrough() throws Exception {
        when(poolService.exportToPool(eq("wf-1"), any(), eq("repo")))
                .thenReturn(new ExportToPoolResponse("build-gate", "build-gate.json", List.of()));

        mockMvc.perform(post("/api/v1/pool/export/wf-1?scope=repo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.poolId").value("build-gate"));
    }

    @Test
    void exportToPool_withoutScopeParam_passesNull() throws Exception {
        when(poolService.exportToPool(eq("wf-1"), any(), eq((String) null)))
                .thenReturn(new ExportToPoolResponse("build-gate", "build-gate.json", List.of()));

        mockMvc.perform(post("/api/v1/pool/export/wf-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isCreated());
    }

    @Test
    void exportToPool_invalidScope_returns400() throws Exception {
        when(poolService.exportToPool(eq("wf-1"), any(), eq("private")))
                .thenThrow(new IllegalArgumentException("Unbekannter scope: private"));

        mockMvc.perform(post("/api/v1/pool/export/wf-1?scope=private")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void install_poolNotFound_returns404() throws Exception {
        when(poolService.install(eq("unknown"), any(InstallPoolWorkflowRequest.class)))
                .thenThrow(new ResourceNotFoundException("PoolWorkflow", "unknown"));

        mockMvc.perform(post("/api/v1/pool/unknown/install")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"projectId": "proj-1"}
                                """))
                .andExpect(status().isNotFound());
    }
}
