package com.automation.config;

import com.automation.security.AllowedOriginHandshakeInterceptor;
import com.automation.security.AllowedOrigins;
import com.automation.security.WebSocketAuthInterceptor;
import com.automation.service.system.OwnTailnetAddresses;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.SockJsServiceRegistration;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.HashMap;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketConfigTest {

    @Mock
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Mock
    private MessageBrokerRegistry messageBrokerRegistry;

    @Mock
    private StompEndpointRegistry stompEndpointRegistry;

    @Mock
    private StompWebSocketEndpointRegistration endpointRegistration;

    @Mock
    private SockJsServiceRegistration sockJsRegistration;

    private AllowedOriginHandshakeInterceptor originInterceptor;

    private WebSocketConfig webSocketConfig;

    @BeforeEach
    void setUp() {
        useConfiguredOrigins("http://localhost:3000,http://localhost:5173");
    }

    /** A real origin check over the given list, without any own Tailscale address. */
    private void useConfiguredOrigins(String origins) {
        OwnTailnetAddresses noTailnet = mock(OwnTailnetAddresses.class);
        lenient().when(noTailnet.hosts()).thenReturn(Set.of());
        originInterceptor = new AllowedOriginHandshakeInterceptor(new AllowedOrigins(origins, noTailnet));
        webSocketConfig = new WebSocketConfig(webSocketAuthInterceptor, originInterceptor);
    }

    private void stubEndpointChain() {
        when(stompEndpointRegistry.addEndpoint("/ws")).thenReturn(endpointRegistration);
        when(endpointRegistration.addInterceptors(any(HandshakeInterceptor[].class))).thenReturn(endpointRegistration);
        when(endpointRegistration.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpointRegistration);
        when(endpointRegistration.withSockJS()).thenReturn(sockJsRegistration);
        lenient().when(sockJsRegistration.setSuppressCors(true)).thenReturn(sockJsRegistration);
    }

    /** Runs the first registered handshake interceptor for a request carrying {@code origin}. */
    private boolean handshakeFrom(String origin) throws Exception {
        ArgumentCaptor<HandshakeInterceptor[]> captor = ArgumentCaptor.forClass(HandshakeInterceptor[].class);
        verify(endpointRegistration).addInterceptors(captor.capture());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws/000/abc/websocket");
        request.setServerPort(9090);
        request.addHeader("Origin", origin);
        return captor.getValue()[0].beforeHandshake(new ServletServerHttpRequest(request),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, new HashMap<>());
    }

    // --- Annotation tests ---

    @Test
    void should_haveConfigurationAnnotation_when_classIsInspected() {
        assertThat(WebSocketConfig.class.isAnnotationPresent(Configuration.class))
                .as("WebSocketConfig must be annotated with @Configuration")
                .isTrue();
    }

    @Test
    void should_haveEnableWebSocketMessageBrokerAnnotation_when_classIsInspected() {
        assertThat(WebSocketConfig.class.isAnnotationPresent(EnableWebSocketMessageBroker.class))
                .as("WebSocketConfig must be annotated with @EnableWebSocketMessageBroker")
                .isTrue();
    }

    @Test
    void should_implementWebSocketMessageBrokerConfigurer_when_classIsInspected() {
        assertThat(WebSocketMessageBrokerConfigurer.class.isAssignableFrom(WebSocketConfig.class))
                .as("WebSocketConfig must implement WebSocketMessageBrokerConfigurer")
                .isTrue();
    }

    // --- configureMessageBroker tests ---

    @Test
    void should_enableSimpleBrokerOnTopic_when_configuringMessageBroker() {
        webSocketConfig.configureMessageBroker(messageBrokerRegistry);

        verify(messageBrokerRegistry).enableSimpleBroker("/topic");
    }

    @Test
    void should_setAppDestinationPrefixToApp_when_configuringMessageBroker() {
        webSocketConfig.configureMessageBroker(messageBrokerRegistry);

        verify(messageBrokerRegistry).setApplicationDestinationPrefixes("/app");
    }

    // --- registerStompEndpoints tests ---

    @Test
    void should_addWsEndpoint_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(stompEndpointRegistry).addEndpoint("/ws");
    }

    @Test
    void should_addAuthInterceptor_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        // Origin first, then the ticket (a8056256)
        verify(endpointRegistration).addInterceptors(originInterceptor, webSocketAuthInterceptor);
    }

    @Test
    void should_leaveTheOriginDecisionToTheInterceptor_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        // Spring's own check only knows a fixed startup list; the interceptor in front decides.
        verify(endpointRegistration).setAllowedOriginPatterns(AllowedOriginHandshakeInterceptor.CHECKED_HERE);
    }

    @Test
    void should_enableSockJS_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(endpointRegistration).withSockJS();
    }

    @Test
    void should_leaveCorsToTheGlobalFilter_when_enablingSockJS() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        // SockJS' own CORS rules would answer with credentials (a8056256)
        verify(sockJsRegistration).setSuppressCors(true);
    }

    // --- Edge cases: custom allowed origins ---

    @Test
    void should_splitMultipleOrigins_when_customOriginsAreConfigured() throws Exception {
        useConfiguredOrigins("https://example.com,https://other.com,https://third.com");
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        assertThat(handshakeFrom("https://other.com")).isTrue();
        assertThat(handshakeFrom("https://third.com")).isTrue();
        assertThat(handshakeFrom("https://evil.example")).isFalse();
    }

    @Test
    void should_handleSingleOrigin_when_onlyOneOriginIsConfigured() throws Exception {
        useConfiguredOrigins("https://production.example.com");
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        assertThat(handshakeFrom("https://production.example.com")).isTrue();
        assertThat(handshakeFrom("http://localhost:3000")).isFalse();
    }
}
