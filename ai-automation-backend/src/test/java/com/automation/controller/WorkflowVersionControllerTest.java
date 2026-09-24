package com.automation.controller;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.response.WorkflowResponse;
import com.automation.dto.response.WorkflowVersionResponse;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowVersion;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.mapper.WorkflowMapper;
import com.automation.mapper.WorkflowVersionMapper;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowVersionController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowVersionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService workflowService;
    @MockitoBean
    private WorkflowMapper workflowMapper;
    @MockitoBean
    private WorkflowVersionService workflowVersionService;
    @MockitoBean
    private WorkflowVersionMapper workflowVersionMapper;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private static WorkflowVersion version(String workflowId, String snapshot) {
        return WorkflowVersion.builder()
                .id("v-1").workflowId(workflowId).versionNumber(3).snapshot(snapshot).build();
    }

    private static WorkflowVersionResponse versionResponse() {
        return new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "user", null, Instant.now());
    }

    private static WorkflowResponse workflowResponse() {
        return new WorkflowResponse(
                "wf-1", "proj-1", "Test Project", null, null,
                "Test Workflow", "A test workflow",
                null, List.of(), "1.0.0", Map.of(), List.of(), List.of(), 0L,
                null, null, null, null, Map.of(), null, null, Instant.now(), Instant.now(), null);
    }

    @Test
    void getVersions_returnsPage() throws Exception {
        WorkflowVersion v = version("wf-1", "{}");
        when(workflowVersionService.findByWorkflowId("wf-1", PageRequest.of(0, 20)))
                .thenReturn(new PageImpl<>(List.of(v), PageRequest.of(0, 20), 1));
        when(workflowVersionMapper.toResponseList(List.of(v))).thenReturn(List.of(versionResponse()));

        mockMvc.perform(get("/api/v1/workflows/wf-1/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value("v-1"))
                .andExpect(jsonPath("$.content[0].versionNumber").value(3))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalElements").value(1));

        verify(workflowService).findById("wf-1");
    }

    @Test
    void getVersion_ofAnotherWorkflow_returns404() throws Exception {
        when(workflowVersionService.findById("v-1")).thenReturn(version("wf-other", "{}"));

        mockMvc.perform(get("/api/v1/workflows/wf-1/versions/v-1"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getVersion_returnsVersion() throws Exception {
        WorkflowVersion v = version("wf-1", "{}");
        when(workflowVersionService.findById("v-1")).thenReturn(v);
        when(workflowVersionMapper.toResponse(v)).thenReturn(versionResponse());

        mockMvc.perform(get("/api/v1/workflows/wf-1/versions/v-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowId").value("wf-1"));
    }

    @Test
    void restoreVersion_restoresSnapshotWithVersionMessage() throws Exception {
        when(workflowVersionService.findById("v-1")).thenReturn(version("wf-1", "{\"model\":\"opus\"}"));
        Workflow restored = Workflow.builder().id("wf-1").name("Test Workflow").build();
        when(workflowService.restoreFromSnapshot(eq("wf-1"), any(WorkflowSnapshot.class), eq("Restored from version 3")))
                .thenReturn(restored);
        when(workflowMapper.toResponse(restored)).thenReturn(workflowResponse());

        mockMvc.perform(post("/api/v1/workflows/wf-1/versions/v-1/restore"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("wf-1"));
    }

    @Test
    void restoreVersion_unreadableSnapshot_returns500WithoutRestoring() throws Exception {
        when(workflowVersionService.findById("v-1")).thenReturn(version("wf-1", "not json"));

        mockMvc.perform(post("/api/v1/workflows/wf-1/versions/v-1/restore"))
                .andExpect(status().isInternalServerError());

        verify(workflowService, never()).restoreFromSnapshot(anyString(), any(), anyString());
    }

    @Test
    void restoreVersion_ofAnotherWorkflow_returns404() throws Exception {
        when(workflowVersionService.findById("v-1")).thenReturn(version("wf-other", "{}"));

        mockMvc.perform(post("/api/v1/workflows/wf-1/versions/v-1/restore"))
                .andExpect(status().isNotFound());
    }
}
