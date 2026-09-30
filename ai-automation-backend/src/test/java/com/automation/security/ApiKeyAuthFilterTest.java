package com.automation.security;

import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ActivityLogService activityLogService;

    /** Echter Request: der Filter matcht den Pfad mit denselben PathPattern-Matchern wie die Filterkette. */
    private MockHttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(apiKeyService, activityLogService);
        request = new MockHttpServletRequest("GET", "/");
        SecurityContextHolder.clearContext();
    }

    @Test
    void securityDisabled_authenticatesAsAnonymous() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", false);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("anonymous");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_blankApiKey_authenticatesAsAnonymous() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("anonymous");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_validApiKey_authenticates() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.addHeader("X-API-Key", "my-secret-key");
        when(apiKeyService.matchesEffectiveApiKey("my-secret-key")).thenReturn(true);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("api-key-user");
        verify(filterChain).doFilter(request, response);
        verify(apiKeyService).matchesEffectiveApiKey("my-secret-key");
    }

    @Test
    void securityEnabled_invalidApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.addHeader("X-API-Key", "wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_missingApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_invalidKey_logsSecurityEvent() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.addHeader("X-API-Key", "wrong-key");
        request.setRequestURI("/api/v1/workflows");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("invalid API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_protectedPath_logsSecurityEvent() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/workflows");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("no API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_publicPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_webhookTriggerPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/webhook/trigger/abc-123");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_systemInfoPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/info");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_authStatusPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/auth-status");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_jvmMemoryPath_noSecurityLog() throws Exception {
        // c2cda5ed: der Performance-Audit liest die Heap-Zahlen ohne Key — jeder Lauf waere
        // sonst ein SECURITY-Eintrag "no API key provided".
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/jvm-memory");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_noKey_otherSystemMetricsPath_stillLogsSecurityEvent() throws Exception {
        // Die Ausnahme ist genau ein Pfad: der Nachbar mit den Request-Kosten bleibt geschuetzt.
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/request-metrics");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("no API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_oauthCallback_noSecurityLog() throws Exception {
        // Der Anbieter ruft ohne Key zurück; die Filterkette lässt den Callback durch — früher hielt
        // der Filter ihn trotzdem für geschützt und schrieb bei jedem OAuth-Login eine Warnung.
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/integrations/github/callback");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
        verify(filterChain).doFilter(request, response);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/metrics",
            "/actuator/metrics/jvm.memory.used",
            "/actuator/info",
            "/actuator/health/liveness",
            "/api/v1/system/health/extra",
            "/api/v1/system/health/",
            "/api/v1/integrations/github/callback/extra",
            "/api/v1/integrations/a/b/callback"
    })
    void securityEnabled_noKey_keyRequiredPath_logsSecurityEvent(String path) throws Exception {
        // Was die Filterkette ohne Key abweist, protokolliert der Filter — auch unter /actuator/**
        // und für die Nachbarn der öffentlichen Pfade.
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI(path);

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("no API key provided for " + path),
                eq("ApiKeyAuthFilter"), isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/actuator/health",
            "/api/v1/webhook/trigger",
            "/ws/info",
            "/",
            "/assets/app.js",
            "/workflows/123"
    })
    void securityEnabled_noKey_publicOrFrontendPath_noSecurityLog(String path) throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI(path);

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }
}
