package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DataSource dataSource;

    @MockitoBean
    private SystemIdentityService systemIdentity;

    @MockitoBean
    private TailscaleService tailscaleService;

    @MockitoBean
    private ActivityLogService activityLogService;

    @MockitoBean
    private ApiKeyService apiKeyService;

    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    @MockitoBean
    private DockerExpectation dockerExpectation;

    private void mockDbHealthy() throws Exception {
        Connection conn = mock(Connection.class);
        Statement stmt = mock(Statement.class);
        when(dataSource.getConnection()).thenReturn(conn);
        when(conn.createStatement()).thenReturn(stmt);
    }

    @Test
    void health_allUp_returnsUp() throws Exception {
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.UP);
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
    void health_dockerConfiguredButUnavailable_returnsDegraded() throws Exception {
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.DOWN);
        mockDbHealthy();

        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DEGRADED"))
                .andExpect(jsonPath("$.docker").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.components.database.status").value("UP"))
                .andExpect(jsonPath("$.components.docker.status").value("DOWN"));
    }

    @Test
    void health_dockerNotConfigured_isNotAnError() throws Exception {
        // a0e85182: eine Installation ohne Docker ist ein Normalzustand, kein gestoerter Betrieb
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.NOT_CONFIGURED);
        mockDbHealthy();

        mockMvc.perform(get("/api/v1/system/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.docker").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.components.docker.status").value("NOT_CONFIGURED"));
    }

    @Test
    void health_dbDown_returnsDown() throws Exception {
        when(dockerExpectation.probe()).thenReturn(DockerExpectation.State.UP);
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
                .andExpect(jsonPath("$.runtimeMode").value(
                        org.hamcrest.Matchers.oneOf("jar", "classes-dir")))
                .andExpect(jsonPath("$.tailscale").value("DISABLED"))
                .andExpect(jsonPath("$.environment").value("Dev"));
    }

    @Test
    void info_carriesRateLimitParametersForTheClient() throws Exception {
        when(systemIdentity.getName()).thenReturn("MySuricates");
        when(rateLimitConfig.isEnabled()).thenReturn(true);
        when(rateLimitConfig.getRequestsPerMinute()).thenReturn(240);
        when(rateLimitConfig.getClientGlobalBackoffSeconds()).thenReturn(3);
        when(rateLimitConfig.getClientDefaultRetryAfterSeconds()).thenReturn(15);

        // Ohne diese Werte rät der Client sein Backoff-Budget (b6feccbc) — eine fremde
        // Instanz mit anderem Limit bekäme falsch geratene Sperrzeiten.
        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimit.enabled").value(true))
                .andExpect(jsonPath("$.rateLimit.requestsPerMinute").value(240))
                .andExpect(jsonPath("$.rateLimit.globalBackoffSeconds").value(3))
                .andExpect(jsonPath("$.rateLimit.defaultRetryAfterSeconds").value(15));
    }

    @Test
    void info_rateLimitDisabled_stillReportsTheClientParameters() throws Exception {
        when(systemIdentity.getName()).thenReturn("MySuricates");
        when(rateLimitConfig.isEnabled()).thenReturn(false);
        when(rateLimitConfig.getClientGlobalBackoffSeconds()).thenReturn(2);
        when(rateLimitConfig.getClientDefaultRetryAfterSeconds()).thenReturn(10);

        mockMvc.perform(get("/api/v1/system/info"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rateLimit.enabled").value(false))
                .andExpect(jsonPath("$.rateLimit.globalBackoffSeconds").value(2))
                .andExpect(jsonPath("$.rateLimit.defaultRetryAfterSeconds").value(10));
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
}
