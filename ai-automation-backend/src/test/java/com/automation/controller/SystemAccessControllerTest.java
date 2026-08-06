package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.TailscaleException;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.FileBrowserService;
import com.automation.service.system.IntegrationSnippetService;
import com.automation.service.system.TailscaleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemAccessController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemAccessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TailscaleService tailscaleService;

    @MockitoBean
    private FileBrowserService fileBrowserService;

    @MockitoBean
    private IntegrationSnippetService integrationSnippetService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void enableRemoteAccess_success() throws Exception {
        when(tailscaleService.enable())
                .thenReturn(new RemoteAccessResponse(true, "100.64.1.2", "my-host"));

        mockMvc.perform(post("/api/v1/system/remote-access/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.ip").value("100.64.1.2"))
                .andExpect(jsonPath("$.hostname").value("my-host"));
    }

    @Test
    void enableRemoteAccess_disabled_returns409() throws Exception {
        when(tailscaleService.enable())
                .thenThrow(new IllegalStateException("Tailscale integration is disabled in configuration"));

        mockMvc.perform(post("/api/v1/system/remote-access/enable"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Tailscale integration is disabled in configuration"));
    }

    @Test
    void enableRemoteAccess_tailscaleNotInstalled_returns503() throws Exception {
        when(tailscaleService.enable())
                .thenThrow(new TailscaleException("Failed to enable Tailscale (exit code: 1)"));

        mockMvc.perform(post("/api/v1/system/remote-access/enable"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void disableRemoteAccess_success() throws Exception {
        doNothing().when(tailscaleService).disable();

        mockMvc.perform(post("/api/v1/system/remote-access/disable"))
                .andExpect(status().isOk());

        verify(tailscaleService).disable();
    }

    @Test
    void remoteAccessStatus_enabled() throws Exception {
        when(tailscaleService.getStatus())
                .thenReturn(new RemoteAccessResponse(true, "100.64.1.2", "my-host"));

        mockMvc.perform(get("/api/v1/system/remote-access/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.ip").value("100.64.1.2"));
    }

    @Test
    void remoteAccessStatus_disabled() throws Exception {
        when(tailscaleService.getStatus())
                .thenReturn(new RemoteAccessResponse(false, null, null));

        mockMvc.perform(get("/api/v1/system/remote-access/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.ip").doesNotExist());
    }

    @Test
    void integrationSnippet_returnsMarkdown() throws Exception {
        when(integrationSnippetService.render(null))
                .thenReturn("# MySuricates Integration\n\n## MySuricates API\n");

        mockMvc.perform(get("/api/v1/system/integration-snippet"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/markdown"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("MySuricates")));
    }

    @Test
    void integrationSnippet_withProjectPath_passesParam() throws Exception {
        when(integrationSnippetService.render("/home/user/project"))
                .thenReturn("# MySuricates Integration\n");

        mockMvc.perform(get("/api/v1/system/integration-snippet")
                        .param("projectPath", "/home/user/project"))
                .andExpect(status().isOk());

        verify(integrationSnippetService).render("/home/user/project");
    }
}
