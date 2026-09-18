package com.automation.security;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.BeforeEach;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
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
        // Beide Stubs gelten für fast alle Tests; die zwei Ausnahmen (Security aus / kein Key
        // konfiguriert) kehren vorher um — deshalb lenient nur hier statt klassenweit.
        lenient().when(apiKeyService.getEffectiveApiKey()).thenReturn("test-key-123");
        lenient().when(request.getServletRequest()).thenReturn(servletRequest);
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
        when(servletRequest.getParameter("ticket")).thenReturn(null);
        when(servletRequest.getParameter("apiKey")).thenReturn("test-key-123");

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).as("the key must never travel in the URL again").isFalse();
        verify(response).setStatusCode(HttpStatus.UNAUTHORIZED);
        verify(apiKeyService, never()).matchesEffectiveApiKey(any());
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
    void aPresentedButInvalidTicketIsLoggedAsWarning() {
        when(servletRequest.getParameter("ticket")).thenReturn("forged-ticket");

        List<ILoggingEvent> events = captureLogs(() ->
                interceptor.beforeHandshake(request, response, handler, new HashMap<>()));

        assertThat(events).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("invalid");
        });
    }

    @Test
    void aMissingTicketIsOnlyLoggedAtDebug() {
        // Browser, Health-Checks und Crawler klopfen ohne Parameter an — das ist kein Sicherheitsvorfall
        // und darf das WARN-Log nicht fluten.
        List<ILoggingEvent> events = captureLogs(() ->
                interceptor.beforeHandshake(request, response, handler, new HashMap<>()));

        assertThat(events).isNotEmpty();
        assertThat(events).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.DEBUG));
    }

    @Test
    void blankApiKeyAllowsAll() {
        when(apiKeyService.getEffectiveApiKey()).thenReturn("");

        boolean result = interceptor.beforeHandshake(request, response, handler, new HashMap<>());

        assertThat(result).isTrue();
        verify(response, never()).setStatusCode(any());
    }

    /** Runs the action with a Logback appender attached to the interceptor's logger at DEBUG. */
    private List<ILoggingEvent> captureLogs(Runnable action) {
        Logger logger = (Logger) LoggerFactory.getLogger(WebSocketAuthInterceptor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(appender);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
        return appender.list;
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
