package com.automation.config;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleHandshakeGuard;
import com.automation.modules.ModuleRegistry;
import com.automation.security.AllowedOriginHandshakeInterceptor;
import com.automation.security.WebSocketAuthInterceptor;
import com.automation.websocket.TerminalWebSocketHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
    private final AllowedOriginHandshakeInterceptor originInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Always registered: the module guard answers the handshake with 404 while 'terminal' is
        // disabled, so a runtime switch (automation.modules.ui-editable) takes effect immediately.
        // The origin interceptor decides instead of Spring's fixed startup list (a8056256).
        registry.addHandler(terminalWebSocketHandler, "/ws/terminal/**")
                .addInterceptors(new ModuleHandshakeGuard(modules, ModuleCatalog.TERMINAL), originInterceptor,
                        webSocketAuthInterceptor)
                .setAllowedOriginPatterns(AllowedOriginHandshakeInterceptor.CHECKED_HERE);
    }
}
