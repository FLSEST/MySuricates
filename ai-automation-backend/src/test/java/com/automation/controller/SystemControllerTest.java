package com.automation.controller;

import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(SystemController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class SystemControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

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

    // --- jvm-memory: drei Zahlen ohne Key statt der Metrik-Oberflaeche (c2cda5ed) ---

    @Test
    void jvmMemory_returnsExactlyTheThreeHeapNumbers() throws Exception {
        String body = mockMvc.perform(get("/api/v1/system/jvm-memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvm_memory_used_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_memory_max_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_heap_used_percent").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Genau diese drei Schluessel, sonst nichts aus der Metrik-Oberflaeche.
        assertThat(objectMapper.readValue(body, Map.class).keySet()).containsExactly(
                "jvm_memory_used_bytes", "jvm_memory_max_bytes", "jvm_heap_used_percent");
    }

    @Test
    void jvmMemory_percentIsUsedOverMax_roundedToOneDecimal() {
        Map<String, Object> numbers = SystemController.jvmMemory(
                new MemoryUsage(0, 268_435_456L, 300_000_000L, 1_073_741_824L));

        assertThat(numbers).containsExactly(
                entry("jvm_memory_used_bytes", 268_435_456L),
                entry("jvm_memory_max_bytes", 1_073_741_824L),
                entry("jvm_heap_used_percent", 25.0));
        assertThat(SystemController.jvmMemory(new MemoryUsage(0, 1L, 3L, 3L)))
                .containsEntry("jvm_heap_used_percent", 33.3);
    }

    @Test
    void jvmMemory_undefinedMax_leavesMaxAndPercentEmpty() {
        // MemoryUsage.getMax() ist -1, wenn die JVM kein Maximum kennt: dann gibt es keinen
        // Anteil, und -1 als "Maximum" darf nicht als Zahl durchgereicht werden.
        Map<String, Object> numbers = SystemController.jvmMemory(new MemoryUsage(0, 1_000L, 2_000L, -1L));

        assertThat(numbers).containsExactly(
                entry("jvm_memory_used_bytes", 1_000L),
                entry("jvm_memory_max_bytes", null),
                entry("jvm_heap_used_percent", null));
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
