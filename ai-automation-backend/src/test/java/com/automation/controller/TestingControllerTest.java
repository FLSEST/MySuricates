package com.automation.controller;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncPreviewItem;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.TestingStatusResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.staging.TestingService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TestingController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class TestingControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockitoBean private TestingService testingService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void status_returnsTestingStatus() throws Exception {
        when(testingService.getStatus()).thenReturn(
                new TestingStatusResponse(true, true, "0.6.6", "2026-04-08T20:00:00Z"));

        mockMvc.perform(get("/api/v1/testing/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerRunning").value(true))
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.testVersion").value("0.6.6"));
    }

    @Test
    void syncPreview_returnsStructuredResponse() throws Exception {
        when(testingService.syncPreview()).thenReturn(new SyncPreview(
                List.of(new SyncPreviewItem("id-1", "Project A", "TestProject", "SKIP")),
                List.of(),
                List.of()
        ));

        mockMvc.perform(get("/api/v1/testing/sync-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projects[0].name").value("Project A"))
                .andExpect(jsonPath("$.projects[0].action").value("SKIP"));
    }

    @Test
    void syncDefinitions_passesRequestBodyToService() throws Exception {
        when(testingService.syncDefinitions(any(SyncDefinitionsRequest.class)))
                .thenReturn(new SyncResult(1, 2, 0, 0, List.of()));

        mockMvc.perform(post("/api/v1/testing/sync-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowIds":["wf-1"],"projectIds":["proj-1"]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectsSynced").value(1))
                .andExpect(jsonPath("$.workflowsSynced").value(2));
    }

    @Test
    void syncDefinitions_withoutBodyAllowsNullRequest() throws Exception {
        when(testingService.syncDefinitions(isNull()))
                .thenReturn(new SyncResult(0, 0, 0, 0, List.of()));

        mockMvc.perform(post("/api/v1/testing/sync-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectsSynced").value(0));
    }

    @Test
    void reportResult_missingWorkflowNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/testing/report-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"status":"PASSED"}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reportResult_validBodyDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/v1/testing/report-result")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"workflowName":"Integration OAuth Tests","projectName":"AutoPilot","status":"PASSED"}
                                """))
                .andExpect(status().isOk());

        verify(testingService).reportTestResult("Integration OAuth Tests", "AutoPilot", "PASSED");
    }

    @Test
    void pullChanges_returnsSyncResult() throws Exception {
        when(testingService.pullChanges()).thenReturn(new SyncResult(0, 3, 0, 1, List.of("warning")));

        mockMvc.perform(post("/api/v1/testing/pull-changes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(3))
                .andExpect(jsonPath("$.skipped").value(1))
                .andExpect(jsonPath("$.errors[0]").value("warning"));
    }
}
