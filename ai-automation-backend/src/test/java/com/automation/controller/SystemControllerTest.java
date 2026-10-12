package com.automation.controller;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.project.DockerExpectation;
import com.automation.service.system.SystemIdentityService;
import com.automation.service.system.TailscaleService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.sql.DataSource;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
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
        // a0e85182: an installation without Docker is a normal state, not a disturbed operation
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

    /** TODO 3af3ac13: line breaks and other control characters in a report must not forge log lines. */
    @Test
    void clientErrors_controlCharactersCannotForgeLogLines() throws Exception {
        Map<String, String> report = Map.of(
                "message", "boom\r\n2026-10-08 12:00:00.000  ERROR 1 --- [main] forged",
                "stack", "Error: x forged\n  at foo",
                "source", "react\u0085forged",
                "url", "http://localhost:3000/\n\u001b[31mfake",
                "timestamp", "2026-10-08T12:00:00Z");

        List<String> logged = captureClientErrorLog(() -> mockMvc.perform(post("/api/v1/system/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isAccepted()));

        assertThat(logged).singleElement().satisfies(line -> {
            assertThat(line).doesNotContainPattern("[\\p{Cc}\\p{Zl}\\p{Zp}]");
            assertThat(line).contains("boom 2026-10-08 12:00:00.000  ERROR 1 --- [main] forged",
                    "source=react forged", "url=http://localhost:3000/ [31mfake", "Error: x forged");
        });
    }

    /** TODO 3af3ac13: an accepted report still ends up as one line of bounded length. */
    @Test
    void clientErrors_longFieldsAreCutToAFixedLengthInTheLog() throws Exception {
        Map<String, String> report = Map.of(
                "message", "m".repeat(9_000),
                "stack", "s".repeat(20_000),
                "source", "react",
                "url", "http://localhost:3000/?q=" + "u".repeat(5_000));

        List<String> logged = captureClientErrorLog(() -> mockMvc.perform(post("/api/v1/system/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isAccepted()));

        assertThat(logged).singleElement().satisfies(line -> {
            assertThat(line.length()).isLessThan(2_500);
            assertThat(line).contains("m".repeat(100) + "…", "u".repeat(100) + "…", "s".repeat(100) + "…");
        });
    }

    /** TODO 3af3ac13: a report beyond the field limits is refused with 400 and leaves no log line. */
    @Test
    void clientErrors_oversizedReport_returns400AndLogsNothing() throws Exception {
        Map<String, String> report = Map.of("message", "m".repeat(10_001), "source", "react");

        List<String> logged = captureClientErrorLog(() -> mockMvc.perform(post("/api/v1/system/client-errors")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(report)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors.message").exists()));

        assertThat(logged).isEmpty();
    }

    @Test
    void logField_keepsShortValues_andNeverCutsASurrogatePairInHalf() {
        assertThat(SystemController.logField(null, 10)).isNull();
        assertThat(SystemController.logField("a\tb\r\nc", 10)).isEqualTo("a b c");
        assertThat(SystemController.logField("a😀b", 2)).isEqualTo("a…");
        assertThat(SystemController.logField("abcdef", 3)).isEqualTo("abc…");
    }

    /** Format characters (bidi overrides, zero-width marks) cannot reorder or hide parts of a log line either. */
    @Test
    void logField_replacesFormatAndBidiControlCharacters() {
        assertThat(SystemController.logField("ok\u202Eevil\u202C|\u2066x\u2069\u200By\uFEFF", 100))
                .isEqualTo("ok evil | x y ")
                .doesNotContainPattern("\\p{Cf}");
    }

    private interface MockMvcCall {
        void run() throws Exception;
    }

    /** Formatted messages of the {@code [CLIENT]} lines the controller logs during {@code call}. */
    private static List<String> captureClientErrorLog(MockMvcCall call) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(SystemController.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        // logback-test.xml runs the root at ERROR; the report is a WARN line
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        try {
            call.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.startsWith("[CLIENT]"))
                .toList();
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

        // Without these values the client guesses its backoff budget (b6feccbc) — a foreign
        // instance with a different limit would get wrongly guessed lock times.
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

    // --- jvm-memory: three numbers without a key instead of the metrics surface (c2cda5ed) ---

    @Test
    void jvmMemory_returnsExactlyTheThreeHeapNumbers() throws Exception {
        String body = mockMvc.perform(get("/api/v1/system/jvm-memory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jvm_memory_used_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_memory_max_bytes").isNumber())
                .andExpect(jsonPath("$.jvm_heap_used_percent").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Exactly these three keys, nothing else from the metrics surface.
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
        // MemoryUsage.getMax() is -1 when the JVM knows no maximum: then there is no
        // share, and -1 as "maximum" must not be passed on as a number.
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
