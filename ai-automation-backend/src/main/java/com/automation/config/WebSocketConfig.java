package com.automation.config;

import com.automation.security.AllowedOriginHandshakeInterceptor;
import com.automation.security.WebSocketAuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final AllowedOriginHandshakeInterceptor originInterceptor;

    @Override
    public void configureMessageBroker(MessageBrokerRegistry config) {
        config.enableSimpleBroker("/topic");
        config.setApplicationDestinationPrefixes("/app");
        // Live-log batches carry sequence numbers; clients de-duplicate by them, so messages to a
        // session must keep their publish order (the outbound channel is a thread pool otherwise).
        config.setPreservePublishOrder(true);
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        // Origin first, then the ticket. Spring's own origin check only knows a fixed startup list and
        // would turn away this instance's Tailscale address — the interceptor decides (a8056256).
        registry.addEndpoint("/ws")
                .addInterceptors(originInterceptor, webSocketAuthInterceptor)
                .setAllowedOriginPatterns(AllowedOriginHandshakeInterceptor.CHECKED_HERE)
                .withSockJS()
                // CORS for /ws runs through the global CorsFilter; SockJS' own rules would add credentials.
                .setSuppressCors(true);
    }
}
