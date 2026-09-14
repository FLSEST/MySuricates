package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleHandshakeGuard;
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
        // Always registered: the module guard answers the handshake with 404 while 'terminal' is
        // disabled, so a runtime switch (automation.modules.ui-editable) takes effect immediately.
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal/**")
                .addInterceptors(new ModuleHandshakeGuard(modules, ModuleCatalog.TERMINAL), webSocketAuthInterceptor)
                .setAllowedOriginPatterns(allowedOrigins.split(","));
    }
}
