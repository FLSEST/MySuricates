package com.automation.config;

import com.automation.modules.ModuleRegistry;
import com.automation.security.AllowedOriginHandshakeInterceptor;
import com.automation.security.AllowedOrigins;
import com.automation.security.WebSocketAuthInterceptor;
import com.automation.service.system.OwnTailnetAddresses;
import com.automation.websocket.TerminalWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RawWebSocketConfigTest {

    @Mock
    private TerminalWebSocketHandler terminalWebSocketHandler;

    @Mock
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Mock
    private WebSocketHandlerRegistry registry;

    @Mock
    private WebSocketHandlerRegistration registration;

    private AllowedOriginHandshakeInterceptor originInterceptor;

    private RawWebSocketConfig config;

    @BeforeEach
    void setUp() {
        useConfiguredOrigins("http://localhost:3000,http://localhost:5173");
        lenient().when(registry.addHandler(any(), any(String[].class))).thenReturn(registration);
        lenient().when(registration.addInterceptors(any(HandshakeInterceptor[].class))).thenReturn(registration);
        lenient().when(registration.setAllowedOriginPatterns(any(String[].class))).thenReturn(registration);
    }

    /** A real origin check over the given list, without any own Tailscale address. */
    private void useConfiguredOrigins(String origins) {
        OwnTailnetAddresses noTailnet = mock(OwnTailnetAddresses.class);
        lenient().when(noTailnet.hosts()).thenReturn(Set.of());
        originInterceptor = new AllowedOriginHandshakeInterceptor(new AllowedOrigins(origins, noTailnet));
        config = new RawWebSocketConfig(terminalWebSocketHandler, webSocketAuthInterceptor,
                ModuleRegistry.allEnabled(), originInterceptor);
    }

    private HandshakeInterceptor[] registeredInterceptors() {
        ArgumentCaptor<HandshakeInterceptor[]> captor = ArgumentCaptor.forClass(HandshakeInterceptor[].class);
        verify(registration).addInterceptors(captor.capture());
        return captor.getValue();
    }

    /** Runs the registered origin interceptor for a terminal handshake carrying {@code origin}. */
    private boolean handshakeFrom(String origin) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/terminal/abc");
        request.setServerPort(9090);
        request.addHeader("Origin", origin);
        return registeredInterceptors()[1].beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, new HashMap<>());
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(RawWebSocketConfig.class.isAnnotationPresent(Configuration.class))
                .as("RawWebSocketConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveEnableWebSocketAnnotation_when_classIsInspected() {
        assertThat(RawWebSocketConfig.class.isAnnotationPresent(EnableWebSocket.class))
                .as("RawWebSocketConfig must be annotated with @EnableWebSocket")
                .isTrue();
    }

    @Test
    void should_implementWebSocketConfigurer_when_classIsInspected() {
        assertThat(WebSocketConfigurer.class).isAssignableFrom(RawWebSocketConfig.class);
    }

    // --- registerWebSocketHandlers tests ---

    @Test
    void should_registerTerminalHandler_when_registerWebSocketHandlersIsCalled() {
        config.registerWebSocketHandlers(registry);

        verify(registry).addHandler(eq(terminalWebSocketHandler), eq("/ws/terminal/**"));
    }

    @Test
    void should_addModuleGuardBeforeAuthInterceptor_when_registerWebSocketHandlersIsCalled() {
        config.registerWebSocketHandlers(registry);

        HandshakeInterceptor[] interceptors = registeredInterceptors();
        assertThat(interceptors).hasSize(3);
        assertThat(interceptors[0]).isInstanceOf(com.automation.modules.ModuleHandshakeGuard.class);
        // Origin before the ticket (a8056256)
        assertThat(interceptors[1]).isSameAs(originInterceptor);
        assertThat(interceptors[2]).isSameAs(webSocketAuthInterceptor);
    }

    @Test
    void should_leaveTheOriginDecisionToTheInterceptor_when_defaultOriginsAreUsed() {
        config.registerWebSocketHandlers(registry);

        // Spring's own check only knows a fixed startup list; the interceptor in front decides.
        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOriginPatterns(captor.capture());
        assertThat(captor.getValue()).containsExactly(AllowedOriginHandshakeInterceptor.CHECKED_HERE);
    }

    @Test
    void should_acceptDefaultOriginsOnly_when_defaultOriginsAreUsed() throws Exception {
        config.registerWebSocketHandlers(registry);

        assertThat(handshakeFrom("http://localhost:5173")).isTrue();
        assertThat(handshakeFrom("https://evil.tail0000.ts.net:3000")).isFalse();
    }

    @Test
    void should_setSingleOriginPattern_when_allowedOriginsHasOneValue() throws Exception {
        useConfiguredOrigins("https://example.com");

        config.registerWebSocketHandlers(registry);

        assertThat(handshakeFrom("https://example.com")).isTrue();
        assertThat(handshakeFrom("http://localhost:3000")).isFalse();
    }

    @Test
    void should_setMultipleOriginPatterns_when_allowedOriginsHasThreeValues() throws Exception {
        useConfiguredOrigins("http://a.com,http://b.com,http://c.com");

        config.registerWebSocketHandlers(registry);

        assertThat(handshakeFrom("http://a.com")).isTrue();
        assertThat(handshakeFrom("http://c.com")).isTrue();
        assertThat(handshakeFrom("http://d.com")).isFalse();
    }

    @Test
    void should_callMethodsInCorrectOrder_when_registerWebSocketHandlersIsCalled() {
        config.registerWebSocketHandlers(registry);

        var inOrder = org.mockito.Mockito.inOrder(registry, registration);
        inOrder.verify(registry).addHandler(eq(terminalWebSocketHandler), eq("/ws/terminal/**"));
        inOrder.verify(registration).addInterceptors(any(HandshakeInterceptor[].class));
        inOrder.verify(registration).setAllowedOriginPatterns(any(String[].class));
    }

    @Test
    void should_registerTerminalHandlerEvenWhenModuleIsDisabled_soRuntimeSwitchesWork() {
        RawWebSocketConfig coreOnly = new RawWebSocketConfig(terminalWebSocketHandler, webSocketAuthInterceptor,
                ModuleRegistry.of(Map.of(), Map.of()), originInterceptor);

        coreOnly.registerWebSocketHandlers(registry);

        verify(registry).addHandler(eq(terminalWebSocketHandler), eq("/ws/terminal/**"));
    }
}
