package com.automation.security;

import com.automation.entity.ActivityLog.LogCategory;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ApiKeyAuthFilterTest {

    private ApiKeyAuthFilter filter;

    @Mock
    private ApiKeyService apiKeyService;

    @Mock
    private ActivityLogService activityLogService;

    /** Real request: the filter matches the path with the same PathPattern matchers as the filter chain. */
    private MockHttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @Mock
    private RunTokenService runTokenService;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(apiKeyService, activityLogService, runTokenService);
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
        // c2cda5ed: the performance audit reads the heap numbers without a key — every run would
        // otherwise be a SECURITY entry "no API key provided".
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
        // The exception is exactly one path: its neighbor with the request costs stays protected.
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        request.setRequestURI("/api/v1/system/request-metrics");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("no API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_oauthCallback_noSecurityLog() throws Exception {
        // The provider calls back without a key; the filter chain lets the callback through — the filter
        // used to still consider it protected and wrote a warning on every OAuth login.
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
        // What the filter chain rejects without a key, the filter logs — also under /actuator/**
        // and for the neighbors of the public paths.
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

    @Nested
    @DisplayName("run token (X-Run-Token)")
    class RunToken {

        private final MockHttpServletResponse servletResponse = new MockHttpServletResponse();

        @BeforeEach
        void keyIsSet() {
            ReflectionTestUtils.setField(filter, "securityEnabled", true);
            when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        }

        private MockHttpServletRequest tokenRequest(String method, String path) {
            MockHttpServletRequest r = new MockHttpServletRequest(method, path);
            r.setServletPath(path);
            r.addHeader("X-Run-Token", "tok-1");
            return r;
        }

        @Test
        @DisplayName("an allowlisted GET passes as the run, with the run-token role only")
        void allowlistedGetPasses() throws Exception {
            when(runTokenService.resolve("tok-1")).thenReturn(Optional.of("run-1"));
            MockHttpServletRequest r = tokenRequest("GET", "/api/v1/todos");

            filter.doFilterInternal(r, servletResponse, filterChain);

            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.getPrincipal()).isEqualTo("run:run-1");
            assertThat(auth.getAuthorities()).extracting(GrantedAuthority::getAuthority).containsExactly("ROLE_RUN_TOKEN");
            verify(filterChain).doFilter(r, servletResponse);
            verify(runTokenService).recordRead("tok-1", "/api/v1/todos");
            verifyNoInteractions(activityLogService);
        }

        @Test
        @DisplayName("a write is refused with 403 RUN_TOKEN_SCOPE and logged")
        void writeIsRefused() throws Exception {
            when(runTokenService.resolve("tok-1")).thenReturn(Optional.of("run-1"));

            filter.doFilterInternal(tokenRequest("POST", "/api/v1/todos"), servletResponse, filterChain);

            assertThat(servletResponse.getStatus()).isEqualTo(403);
            assertThat(servletResponse.getContentAsString()).contains("RUN_TOKEN_SCOPE");
            verify(filterChain, never()).doFilter(any(), any());
            verify(activityLogService).warn(eq(LogCategory.SECURITY), contains("POST /api/v1/todos"),
                    eq("ApiKeyAuthFilter"), eq("run-1"));
            assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        }

        @Test
        @DisplayName("a GET outside the allowlist is refused as well")
        void otherGetIsRefused() throws Exception {
            when(runTokenService.resolve("tok-1")).thenReturn(Optional.of("run-1"));

            filter.doFilterInternal(tokenRequest("GET", "/api/v1/system/settings"), servletResponse, filterChain);

            assertThat(servletResponse.getStatus()).isEqualTo(403);
            verify(filterChain, never()).doFilter(any(), any());
        }

        @Test
        @DisplayName("an unknown or revoked token gets 401 RUN_TOKEN_INVALID")
        void unknownTokenIsRejected() throws Exception {
            when(runTokenService.resolve("tok-1")).thenReturn(Optional.empty());

            filter.doFilterInternal(tokenRequest("GET", "/api/v1/todos"), servletResponse, filterChain);

            assertThat(servletResponse.getStatus()).isEqualTo(401);
            assertThat(servletResponse.getContentAsString()).contains("RUN_TOKEN_INVALID");
            verify(filterChain, never()).doFilter(any(), any());
            verify(activityLogService).warn(eq(LogCategory.SECURITY), contains("run token"), eq("ApiKeyAuthFilter"), eq(null));
        }

        @Test
        @DisplayName("a valid API key wins over a run token")
        void apiKeyWins() throws Exception {
            MockHttpServletRequest r = tokenRequest("POST", "/api/v1/todos");
            r.addHeader("X-API-Key", "my-secret-key");
            when(apiKeyService.matchesEffectiveApiKey("my-secret-key")).thenReturn(true);

            filter.doFilterInternal(r, servletResponse, filterChain);

            assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal()).isEqualTo("api-key-user");
            verify(filterChain).doFilter(r, servletResponse);
            verifyNoInteractions(runTokenService);
        }
    }
}
