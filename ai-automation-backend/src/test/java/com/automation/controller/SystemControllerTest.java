package com.automation.controller;

import com.automation.dto.response.RemoteAccessResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.TailscaleException;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.ApiKeyService;
import com.automation.service.system.DatabaseBackupService;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.PingCmd;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private DockerClient dockerClient;

    @MockBean
    private TailscaleService tailscaleService;

    @MockBean
    private DatabaseBackupService databaseBackupService;

    @MockBean
    private ApiKeyService apiKeyService;

    @MockBean
    private AppSettingsRepository appSettingsRepository;

    @MockBean
    private DataSource dataSource;

    @MockBean
    private SystemIdentityService systemIdentity;

    @MockBean
    private com.automation.service.repair.AutoRepairService autoRepairService;

    @MockBean
    private com.automation.service.ai.AiProviderRegistry aiProviderRegistry;

    private void mockDbHealthy() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
    }

    @Test
    void health_allUp_returnsUp() throws Exception {
        PingCmd pingCmd = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(pingCmd);
        mockDbHealthy();

        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.docker").value("CONNECTED"))
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.docker.status").value("UP"));
    }

    @Test
    void health_dockerUnavailable_returnsDegraded() throws Exception {
        when(dockerClient.pingCmd()).thenThrow(new RuntimeException("Docker not available"));
        mockDbHealthy();

        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.docker").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.docker.status").value("DOWN"));
    }

    @Test
    void health_dbDown_returnsDown() throws Exception {
        PingCmd pingCmd = mock(PingCmd.class);
        when(dockerClient.pingCmd()).thenReturn(pingCmd);
        when(dataSource.getConnection()).thenThrow(new RuntimeException("DB connection failed"));

        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database.status").value("DOWN"));
    }

    @Test
    void clientErrors_logsAndReturnsAccepted() throws Exception {
        mockMvc.perform(post("/api/v1/system/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"test error\",\"stack\":\"Error: test\\n  at foo\",\"source\":\"react\",\"url\":\"http://localhost:3000/\",\"timestamp\":\"2026-01-01T00:00:00Z\"}"))
                .andExpect(status().isAccepted());
    }

    @Test
    void info_returnsPlatformInfo() throws Exception {
        when(systemIdentity.getName()).thenReturn("MySuricates");
        when(systemIdentity.getVersion()).thenReturn("0.2.3");
        when(systemIdentity.getBuildType()).thenReturn("SNAPSHOT");
        when(systemIdentity.getBuildTimestamp()).thenReturn("unknown");
        when(tailscaleService.isConfigEnabled()).thenReturn(false);

        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("MySuricates"))
                .andExpect(jsonPath("$.version").value("0.2.3"))
                .andExpect(jsonPath("$.buildType").value("SNAPSHOT"))
                .andExpect(jsonPath("$.java").exists())
                .andExpect(jsonPath("$.tailscale").value("DISABLED"))
                .andExpect(jsonPath("$.environment").value("Dev"));
    }

    @Test
    void info_tailscaleEnabled_showsConfigured() throws Exception {
        when(systemIdentity.getName()).thenReturn("MySuricates");
        when(systemIdentity.getVersion()).thenReturn("0.2.3");
        when(systemIdentity.getBuildType()).thenReturn("RELEASE");
        when(systemIdentity.getBuildTimestamp()).thenReturn("unknown");
        when(tailscaleService.isConfigEnabled()).thenReturn(true);

        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tailscale").value("CONFIGURED"));
    }

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
    void getSetting_defaultAiProvider_returnsValue() throws Exception {
        when(appSettingsRepository.get("default-ai-provider")).thenReturn(Optional.of("CLAUDE"));

        mockMvc.perform(get("/api/v1/system/settings/default-ai-provider"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.key").value("default-ai-provider"))
                .andExpect(jsonPath("$.value").value("CLAUDE"));
    }

    @Test
    void setSetting_defaultAiProvider_succeeds() throws Exception {
        mockMvc.perform(put("/api/v1/system/settings/default-ai-provider")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"value\":\"CODEX\"}"))
                .andExpect(status().isOk());

        verify(appSettingsRepository).set("default-ai-provider", "CODEX");
    }

    @Test
    void getSetting_disallowedKey_returns400() throws Exception {
        mockMvc.perform(get("/api/v1/system/settings/secret-key"))
                .andExpect(status().isBadRequest());
    }
}
