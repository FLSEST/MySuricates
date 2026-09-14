package com.automation.config;

import com.automation.modules.ModuleRegistry;
import com.automation.security.WebSocketAuthInterceptor;
import com.automation.websocket.TerminalWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistration;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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

    private RawWebSocketConfig config;

    @BeforeEach
    void setUp() throws Exception {
        config = new RawWebSocketConfig(terminalWebSocketHandler, webSocketAuthInterceptor, ModuleRegistry.allEnabled());
        setAllowedOrigins("http://localhost:3000,http://localhost:5173");
        lenient().when(registry.addHandler(any(), any(String[].class))).thenReturn(registration);
        lenient().when(registration.addInterceptors(any(HandshakeInterceptor[].class))).thenReturn(registration);
        lenient().when(registration.setAllowedOriginPatterns(any(String[].class))).thenReturn(registration);
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

        ArgumentCaptor<HandshakeInterceptor[]> captor = ArgumentCaptor.forClass(HandshakeInterceptor[].class);
        verify(registration).addInterceptors(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue()[0]).isInstanceOf(com.automation.modules.ModuleHandshakeGuard.class);
        assertThat(captor.getValue()[1]).isSameAs(webSocketAuthInterceptor);
    }

    @Test
    void should_setDefaultAllowedOriginPatterns_when_defaultOriginsAreUsed() {
        config.registerWebSocketHandlers(registry);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOriginPatterns(captor.capture());
        assertThat(captor.getValue())
                .containsExactly("http://localhost:3000", "http://localhost:5173");
    }

    @Test
    void should_setSingleOriginPattern_when_allowedOriginsHasOneValue() throws Exception {
        setAllowedOrigins("https://example.com");

        config.registerWebSocketHandlers(registry);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOriginPatterns(captor.capture());
        assertThat(captor.getValue())
                .containsExactly("https://example.com");
    }

    @Test
    void should_setMultipleOriginPatterns_when_allowedOriginsHasThreeValues() throws Exception {
        setAllowedOrigins("http://a.com,http://b.com,http://c.com");

        config.registerWebSocketHandlers(registry);

        ArgumentCaptor<String[]> captor = ArgumentCaptor.forClass(String[].class);
        verify(registration).setAllowedOriginPatterns(captor.capture());
        assertThat(captor.getValue())
                .containsExactly("http://a.com", "http://b.com", "http://c.com")
                .hasSize(3);
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
    void should_registerTerminalHandlerEvenWhenModuleIsDisabled_soRuntimeSwitchesWork() throws Exception {
        RawWebSocketConfig coreOnly = new RawWebSocketConfig(terminalWebSocketHandler, webSocketAuthInterceptor,
                ModuleRegistry.of(Map.of(), Map.of()));
        Field origins = RawWebSocketConfig.class.getDeclaredField("allowedOrigins");
        origins.setAccessible(true);
        origins.set(coreOnly, "http://localhost:3000");

        coreOnly.registerWebSocketHandlers(registry);

        verify(registry).addHandler(eq(terminalWebSocketHandler), eq("/ws/terminal/**"));
    }

    // --- Helper ---

    private void setAllowedOrigins(String value) throws Exception {
        Field field = RawWebSocketConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(config, value);
    }
}
