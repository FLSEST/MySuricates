package com.automation.security;

import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;

import jakarta.servlet.http.HttpServletRequest;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WebSocketAuthInterceptorTest {

    /** Test clock that can be moved forward — the ticket service only reads {@code instant()}. */
    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-09-18T20:00:00Z");

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
        void advance(Duration amount) { now = now.plus(amount); }
    }

    @Mock private ApiKeyService apiKeyService;
    @Mock private ServletServerHttpRequest request;
    @Mock private HttpServletRequest servletRequest;
    @Mock private ServerHttpResponse response;
    @Mock private WebSocketHandler handler;

    private final MutableClock clock = new MutableClock();
    private WebSocketTicketService ticketService;
    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        ticketService = new WebSocketTicketService(clock, 30);
        interceptor = new WebSocketAuthInterceptor(apiKeyService, ticketService);
        // Security enabled by default — use reflection to set the field
        setSecurityEnabled(true);
        when(apiKeyService.getEffectiveApiKey()).thenReturn("test-key-123");
        when(request.getServletRequest()).thenReturn(servletRequest);
    }

    @Test
    void validTicketAllowsHandshake() {
        String ticket = ticketService.issue();
        when(servletRequest.getParameter("ticket")).thenReturn(ticket);

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isTrue();
        verify(response, never()).setStatusCode(any());
    }

    @Test
    void ticketIsSingleUse() {
        String ticket = ticketService.issue();
        when(servletRequest.getParameter("ticket")).thenReturn(ticket);
        assertThat(interceptor.beforeHandshake(request, response, handler, new HashMap<>())).isTrue();

        boolean replay = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(replay).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTicketRejectsHandshake() {
        String ticket = ticketService.issue();
        when(servletRequest.getParameter("ticket")).thenReturn(ticket);
        clock.advance(Duration.ofSeconds(31));

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void unknownTicketRejectsHandshake() {
        when(servletRequest.getParameter("ticket")).thenReturn("forged-ticket");

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void apiKeyInQueryStringIsNoLongerAccepted() {
        when(servletRequest.getParameter("apiKey")).thenReturn("test-key-123");
        when(apiKeyService.matchesEffectiveApiKey("test-key-123")).thenReturn(true);

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).as("the key must never travel in the URL again").isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void missingParameterRejectsHandshake() {
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
