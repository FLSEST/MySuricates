package com.automation.controller;

import com.automation.dto.response.StagingStatusResponse;
import com.automation.dto.response.SyncResult;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.staging.StagingService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(StagingController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class StagingControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StagingService stagingService;
    @MockitoBean
    private ActivityLogService activityLogService;


    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void status_returnsCurrentState() throws Exception {
        when(stagingService.getStatus()).thenReturn(
                new StagingStatusResponse(true, true, "IDLE", 0, 5, "stable-20260225-091500", null, "0.1.0", "2026-02-25T09:15:00Z"));

        mockMvc.perform(get("/api/v1/staging/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerRunning").value(true))
                .andExpect(jsonPath("$.healthy").value(true))
                .andExpect(jsonPath("$.promotePhase").value("IDLE"))
                .andExpect(jsonPath("$.promoteTotalSteps").value(5))
                .andExpect(jsonPath("$.lastSnapshot").value("stable-20260225-091500"));
    }

    @Test
    void status_stopped_returnsOffline() throws Exception {
        when(stagingService.getStatus()).thenReturn(
                new StagingStatusResponse(false, false, "IDLE", 0, 5, null, null, null, null));

        mockMvc.perform(get("/api/v1/staging/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.containerRunning").value(false))
                .andExpect(jsonPath("$.healthy").value(false))
                .andExpect(jsonPath("$.lastSnapshot").doesNotExist());
    }

    @Test
    void start_callsService() throws Exception {
        doNothing().when(stagingService).start();

        mockMvc.perform(post("/api/v1/staging/start"))
                .andExpect(status().isOk());

        verify(stagingService).start();
    }

    @Test
    void start_noSnapshot_returns409() throws Exception {
        doThrow(new IllegalStateException("No stable-frontend/ found. Run promote first."))
                .when(stagingService).start();

        mockMvc.perform(post("/api/v1/staging/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("No stable-frontend/ found. Run promote first."));
    }

    @Test
    void stop_callsService() throws Exception {
        doNothing().when(stagingService).stop();

        mockMvc.perform(post("/api/v1/staging/stop"))
                .andExpect(status().isOk());

        verify(stagingService).stop();
    }

    @Test
    void promote_returnsAccepted() throws Exception {
        doNothing().when(stagingService).promote();

        mockMvc.perform(post("/api/v1/staging/promote"))
                .andExpect(status().isAccepted());

        verify(stagingService).promote();
    }

    @Test
    void promote_alreadyRunning_returns409() throws Exception {
        doThrow(new IllegalStateException("Promote already in progress: BUILDING_BACKEND"))
                .when(stagingService).promote();

        mockMvc.perform(post("/api/v1/staging/promote"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void cancelPromote_callsService() throws Exception {
        doNothing().when(stagingService).cancelPromote();

        mockMvc.perform(post("/api/v1/staging/promote/cancel"))
                .andExpect(status().isOk());

        verify(stagingService).cancelPromote();
    }

    @Test
    void syncDefinitions_returnsSyncResult() throws Exception {
        when(stagingService.syncDefinitions(any())).thenReturn(
                new SyncResult(2, 5, 1, 3, List.of()));

        mockMvc.perform(post("/api/v1/staging/sync-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectsSynced").value(2))
                .andExpect(jsonPath("$.workflowsSynced").value(5))
                .andExpect(jsonPath("$.skillsSynced").value(1))
                .andExpect(jsonPath("$.skipped").value(3))
                .andExpect(jsonPath("$.errors").isArray());

        verify(stagingService).syncDefinitions(any());
    }

    @Test
    void syncDefinitions_stableNotRunning_returns409() throws Exception {
        when(stagingService.syncDefinitions(any())).thenThrow(
                new IllegalStateException("Stable instance is not running or not healthy. Start it first."));

        mockMvc.perform(post("/api/v1/staging/sync-definitions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void syncDefinitions_withErrors_returnsPartialResult() throws Exception {
        when(stagingService.syncDefinitions(any())).thenReturn(
                new SyncResult(1, 3, 0, 0, List.of("Workflow 'test': HTTP 500")));

        mockMvc.perform(post("/api/v1/staging/sync-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(3))
                .andExpect(jsonPath("$.errors[0]").value("Workflow 'test': HTTP 500"));
    }
}
