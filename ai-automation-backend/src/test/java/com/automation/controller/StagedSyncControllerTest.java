package com.automation.controller;

import com.automation.dto.request.SyncDefinitionsRequest;
import com.automation.dto.response.SyncPreview;
import com.automation.dto.response.SyncResult;
import com.automation.dto.response.SyncTargetResponse;
import com.automation.exception.ErrorMessage;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.LocalizedStateException;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.staging.StagedSyncService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(StagedSyncController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class StagedSyncControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private StagedSyncService stagedSyncService;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void targets_listsTheConfiguredTargets() throws Exception {
        when(stagedSyncService.targets()).thenReturn(List.of(
                new SyncTargetResponse("test", "http://localhost:8090", true, true, false, "0.8.0"),
                new SyncTargetResponse("prod", "http://localhost:9091", false, false, true, null)));

        mockMvc.perform(get("/api/v1/staging/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("test"))
                .andExpect(jsonPath("$[0].keyPresent").value(true))
                .andExpect(jsonPath("$[0].version").value("0.8.0"))
                .andExpect(jsonPath("$[1].name").value("prod"))
                .andExpect(jsonPath("$[1].sealedOnly").value(true))
                .andExpect(jsonPath("$[1].reachable").value(false));
    }

    @Test
    void syncPreview_returnsThePreviewOfTheTarget() throws Exception {
        when(stagedSyncService.preview("prod")).thenReturn(
                new SyncPreview(List.of(), List.of(), List.of(), List.of(), List.of("chain C left out")));

        mockMvc.perform(get("/api/v1/staging/targets/prod/sync-preview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.notes[0]").value("chain C left out"))
                .andExpect(jsonPath("$.workflows").isArray());
    }

    @Test
    void syncPreview_unconfiguredTarget_is409WithATranslatableMessage() throws Exception {
        when(stagedSyncService.preview("test")).thenThrow(
                new LocalizedStateException(ErrorMessage.SYNC_TARGET_NOT_CONFIGURED, Map.of("target", "test")));

        mockMvc.perform(get("/api/v1/staging/targets/test/sync-preview"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("SYNC_TARGET_NOT_CONFIGURED"))
                .andExpect(jsonPath("$.params.target").value("test"));
    }

    @Test
    void syncDefinitions_withoutBody_passesNullSelection() throws Exception {
        when(stagedSyncService.sync(eq("test"), isNull()))
                .thenReturn(new SyncResult(1, 2, 3, 4, 0, List.of(), "backup.db"));

        mockMvc.perform(post("/api/v1/staging/targets/test/sync-definitions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.projectsSynced").value(1))
                .andExpect(jsonPath("$.workflowsSynced").value(2))
                .andExpect(jsonPath("$.skillsSynced").value(3))
                .andExpect(jsonPath("$.chainsSynced").value(4))
                .andExpect(jsonPath("$.preSyncBackup").value("backup.db"));
    }

    @Test
    void syncDefinitions_withBody_passesTheSelectedIds() throws Exception {
        when(stagedSyncService.sync(eq("prod"), org.mockito.ArgumentMatchers.any()))
                .thenReturn(new SyncResult(0, 1, 0, 0, List.of()));

        mockMvc.perform(post("/api/v1/staging/targets/prod/sync-definitions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowIds\": [\"w1\"], \"chainIds\": [\"c1\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.workflowsSynced").value(1));

        ArgumentCaptor<SyncDefinitionsRequest> captor = ArgumentCaptor.forClass(SyncDefinitionsRequest.class);
        verify(stagedSyncService).sync(eq("prod"), captor.capture());
        assertThat(captor.getValue().workflowIds()).containsExactly("w1");
        assertThat(captor.getValue().chainIds()).containsExactly("c1");
        assertThat(captor.getValue().projectIds()).isNull();
    }

    @Test
    void syncDefinitions_unreachableTarget_is409() throws Exception {
        when(stagedSyncService.sync(eq("test"), isNull())).thenThrow(new LocalizedStateException(
                ErrorMessage.SYNC_TARGET_UNREACHABLE, Map.of("target", "test", "url", "http://localhost:8090")));

        mockMvc.perform(post("/api/v1/staging/targets/test/sync-definitions"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.messageKey").value("SYNC_TARGET_UNREACHABLE"));
    }
}
