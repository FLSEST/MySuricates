package com.automation.modules;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

/**
 * WebSocket counterpart of {@link ModuleGuardInterceptor}: refuses the handshake with 404 while
 * the module is disabled. Checked per handshake so runtime switches take effect immediately.
 */
public class ModuleHandshakeGuard implements HandshakeInterceptor {

    private final ModuleRegistry modules;
    private final ModuleCatalog module;

    public ModuleHandshakeGuard(ModuleRegistry modules, ModuleCatalog module) {
        this.modules = modules;
        this.module = module;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (modules.isEnabled(module)) {
            return true;
        }
        response.setStatusCode(HttpStatus.NOT_FOUND);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // nothing to do
    }
}
