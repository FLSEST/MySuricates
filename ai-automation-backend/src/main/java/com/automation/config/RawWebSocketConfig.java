package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.security.WebSocketAuthInterceptor;
import com.automation.websocket.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Slf4j
@Configuration
@EnableWebSocket
@RequiredArgsConstructor
public class RawWebSocketConfig implements WebSocketConfigurer {

    private final TerminalWebSocketHandler terminalWebSocketHandler;
    private final WebSocketAuthInterceptor webSocketAuthInterceptor;
    private final ModuleRegistry modules;

    @Value("${automation.cors.allowed-origins:http://localhost:3000,http://localhost:5173}")
    private String allowedOrigins;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Terminal module off ⇒ the endpoint does not exist at all (handshake answers 404).
        if (!modules.isEnabled(ModuleCatalog.TERMINAL)) {
            log.info("Terminal WebSocket not registered — module 'terminal' is disabled");
            return;
        }
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal/**")
                .addInterceptors(webSocketAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
