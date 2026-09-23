package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.ExecutionSettingsService;
import com.automation.service.system.SystemSettingsService;
import com.automation.service.tray.TraySettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemSettingsController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemSettingsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SystemSettingsService systemSettingsService;

    @MockitoBean
    private ExecutionSettingsService executionSettingsService;

    @MockitoBean
    private TraySettingsService traySettingsService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void getSetting_defaultAiProvider_returnsValue() throws Exception {
        when(systemSettingsService.isAllowedKey("default-ai-provider")).thenReturn(true);
        when(systemSettingsService.getSetting("default-ai-provider")).thenReturn(Optional.of("CLAUDE"));

        mockMvc.perform(get("/api/v1/system/settings/default-ai-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("default-ai-provider"))
                .andExpect(jsonPath("$.value").value("CLAUDE"));
    }

    @Test
    void setSetting_defaultAiProvider_succeeds() throws Exception {
        when(systemSettingsService.isAllowedKey("default-ai-provider")).thenReturn(true);

        mockMvc.perform(put("/api/v1/system/settings/default-ai-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"CODEX\"}"))
                .andExpect(status().isOk());

        verify(systemSettingsService).setSetting("default-ai-provider", "CODEX");
    }

    @Test
    void getSetting_disallowedKey_returns400() throws Exception {
        when(systemSettingsService.isAllowedKey("secret-key")).thenReturn(false);

        mockMvc.perform(get("/api/v1/system/settings/secret-key"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getExecutionConfig_returnsCurrentSettings() throws Exception {
        when(executionSettingsService.getCurrentSettings())
                .thenReturn(java.util.Map.of("claude-max-concurrent-calls", 5, "claude-reserved-for-interactive", 1));

        mockMvc.perform(get("/api/v1/system/execution-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['claude-max-concurrent-calls']").value(5))
                .andExpect(jsonPath("$.['claude-reserved-for-interactive']").value(1));
    }

    @Test
    void updateExecutionConfig_validSettings_returns200WithUpdated() throws Exception {
        java.util.Map<String, Object> updated = java.util.Map.of("claude-max-concurrent-calls", 7);
        when(executionSettingsService.getCurrentSettings()).thenReturn(updated);

        mockMvc.perform(put("/api/v1/system/execution-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claude-max-concurrent-calls\":\"7\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.['claude-max-concurrent-calls']").value(7));

        verify(executionSettingsService).updateAll(java.util.Map.of("claude-max-concurrent-calls", "7"));
    }

    @Test
    void updateExecutionConfig_invalidSettings_returns400WithError() throws Exception {
        doThrow(new IllegalArgumentException("Invalid value for claude-max-concurrent-calls: must be 1-20"))
                .when(executionSettingsService).updateAll(any());

        mockMvc.perform(put("/api/v1/system/execution-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"claude-max-concurrent-calls\":\"999\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Invalid value for claude-max-concurrent-calls: must be 1-20"));
    }

    @Test
    void getTrayConfig_returnsPopupChoicesAndBrowserTarget() throws Exception {
        when(traySettingsService.getCurrentSettings()).thenReturn(java.util.Map.of(
                "popupsEnabled", true,
                "notifyApprovals", true,
                "notifyFailures", true,
                "notifySuccess", false,
                "publicUrl", "https://mysuricates.example.com"
        ));

        mockMvc.perform(get("/api/v1/system/tray-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popupsEnabled").value(true))
                .andExpect(jsonPath("$.notifySuccess").value(false))
                .andExpect(jsonPath("$.publicUrl").value("https://mysuricates.example.com"));
    }

    @Test
    void updateTrayConfig_returnsValidatedSettings() throws Exception {
        when(traySettingsService.update(any())).thenReturn(java.util.Map.of(
                "popupsEnabled", false,
                "notifyApprovals", true,
                "notifyFailures", true,
                "notifySuccess", false,
                "publicUrl", "https://mysuricates.example.com"
        ));

        mockMvc.perform(put("/api/v1/system/tray-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"popupsEnabled\":false,\"publicUrl\":\"https://mysuricates.example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.popupsEnabled").value(false));

        verify(traySettingsService).update(java.util.Map.of(
                "popupsEnabled", false,
                "publicUrl", "https://mysuricates.example.com"
        ));
    }

    @Test
    void updateTrayConfig_rejectsInvalidExternalHttpUrl() throws Exception {
        doThrow(new IllegalArgumentException("publicUrl must use HTTPS"))
                .when(traySettingsService).update(any());

        mockMvc.perform(put("/api/v1/system/tray-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"publicUrl\":\"http://example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("publicUrl must use HTTPS"));
    }

    /** 8dbd7e10: a refused retention change answers 403, not 400 or 500. */
    @Test
    void updateExecutionConfig_shorterRetentionWhileDeletionIsOff_returns403() throws Exception {
        doThrow(new HardeningException(ErrorCode.RUN_DELETION_DISABLED, "only extend"))
                .when(executionSettingsService).updateAll(anyMap());

        mockMvc.perform(put("/api/v1/system/execution-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"run-retention-days\":\"7\"}"))
                .andExpect(status().isForbidden());
    }
}
