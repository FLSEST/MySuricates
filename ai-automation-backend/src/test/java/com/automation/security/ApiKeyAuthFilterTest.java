package com.automation.security;

import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.system.ApiKeyService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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

    @Mock
    private HttpServletRequest request;

    @Mock
    private HttpServletResponse response;

    @Mock
    private FilterChain filterChain;

    @BeforeEach
    void setUp() {
        filter = new ApiKeyAuthFilter(apiKeyService, activityLogService);
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
        when(request.getHeader("X-API-Key")).thenReturn("my-secret-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNotNull();
        assertThat(SecurityContextHolder.getContext().getAuthentication().getPrincipal())
                .isEqualTo("api-key-user");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_invalidApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_missingApiKey_doesNotAuthenticate() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void securityEnabled_invalidKey_logsSecurityEvent() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn("wrong-key");
        when(request.getRequestURI()).thenReturn("/api/v1/workflows");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("invalid API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_protectedPath_logsSecurityEvent() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService).warn(any(), contains("no API key"), eq("ApiKeyAuthFilter"), isNull());
    }

    @Test
    void securityEnabled_noKey_publicPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/system/health");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_webhookTriggerPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/webhook/trigger/abc-123");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_systemInfoPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/system/info");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }

    @Test
    void securityEnabled_noKey_authStatusPath_noSecurityLog() throws Exception {
        ReflectionTestUtils.setField(filter, "securityEnabled", true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("my-secret-key");
        when(request.getHeader("X-API-Key")).thenReturn(null);
        when(request.getRequestURI()).thenReturn("/api/v1/system/auth-status");

        filter.doFilterInternal(request, response, filterChain);

        verify(activityLogService, never()).warn(any(), anyString(), anyString(), any());
    }
}
