package com.automation.security;

import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import jakarta.servlet.http.HttpServletRequest;

import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock private ApiKeyService apiKeyService;
    @Mock private ServletServerHttpRequest request;
    @Mock private HttpServletRequest servletRequest;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler handler;

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(apiKeyService);
        // Security enabled by default — use reflection to set the field
        setSecurityEnabled(true);
    }

    @Test
    void validApiKeyAllowsHandshake() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("test-key-123");
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getParameter("apiKey")).thenReturn("test-key-123");

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isTrue();
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void missingApiKeyRejectsHandshake() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("test-key-123");
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getParameter("apiKey")).thenReturn(null);
        when(request.getRemoteAddress()).thenReturn(null);

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void wrongApiKeyRejectsHandshake() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("test-key-123");
        when(request.getServletRequest()).thenReturn(servletRequest);
        when(servletRequest.getParameter("apiKey")).thenReturn("wrong-key");
        when(request.getRemoteAddress()).thenReturn(null);

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void securityDisabledAllowsAll() {
        setSecurityEnabled(false);

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isTrue();
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void blankApiKeyAllowsAll() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("");

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isTrue();
        verify(response, never()).setStatusCode(any());
    }

    private void setSecurityEnabled(boolean enabled) {
        try {
            var field = WebSocketAuthInterceptor.class.getDeclaredField("securityEnabled");
            field.setAccessible(true);
            field.set(interceptor, enabled);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
