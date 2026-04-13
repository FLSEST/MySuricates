package com.automation.config;

import com.automation.security.WebSocketAuthInterceptor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.StompWebSocketEndpointRegistration;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.lang.reflect.Field;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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

    private WebSocketConfig webSocketConfig;

    @BeforeEach
    void setUp() throws Exception {
        webSocketConfig = new WebSocketConfig(webSocketAuthInterceptor);
        setAllowedOrigins("http://localhost:3000,http://localhost:5173");
    }

    private void setAllowedOrigins(String origins) throws Exception {
        Field field = WebSocketConfig.class.getDeclaredField("allowedOrigins");
        field.setAccessible(true);
        field.set(webSocketConfig, origins);
    }

    private void stubEndpointChain() {
        when(stompEndpointRegistry.addEndpoint("/ws")).thenReturn(endpointRegistration);
        when(endpointRegistration.addInterceptors(any())).thenReturn(endpointRegistration);
        when(endpointRegistration.setAllowedOriginPatterns(any(String[].class))).thenReturn(endpointRegistration);
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

        verify(endpointRegistration).addInterceptors(webSocketAuthInterceptor);
    }

    @Test
    void should_setDefaultAllowedOriginPatterns_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(endpointRegistration).setAllowedOriginPatterns("http://localhost:3000", "http://localhost:5173");
    }

    @Test
    void should_enableSockJS_when_registeringStompEndpoints() {
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(endpointRegistration).withSockJS();
    }

    // --- Edge cases: custom allowed origins ---

    @Test
    void should_splitMultipleOrigins_when_customOriginsAreConfigured() throws Exception {
        setAllowedOrigins("https://example.com,https://other.com,https://third.com");
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(endpointRegistration).setAllowedOriginPatterns("https://example.com", "https://other.com", "https://third.com");
    }

    @Test
    void should_handleSingleOrigin_when_onlyOneOriginIsConfigured() throws Exception {
        setAllowedOrigins("https://production.example.com");
        stubEndpointChain();

        webSocketConfig.registerStompEndpoints(stompEndpointRegistry);

        verify(endpointRegistration).setAllowedOriginPatterns("https://production.example.com");
    }
}
