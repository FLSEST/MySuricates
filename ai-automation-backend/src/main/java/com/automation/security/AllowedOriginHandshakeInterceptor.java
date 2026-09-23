package com.automation.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;
import org.springframework.web.util.WebUtils;

import java.util.Map;

/**
 * Origin check for both WebSocket handshakes ({@code /ws}, {@code /ws/terminal/**}) with the answer
 * CORS gives ({@link AllowedOrigins}) — including this instance's own Tailscale addresses, which are
 * only known at runtime (a8056256). Spring's built-in check takes a fixed list at startup and would
 * turn them away; the registrations therefore hand it {@link #CHECKED_HERE} and register this
 * interceptor in front of everything else.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AllowedOriginHandshakeInterceptor implements HandshakeInterceptor {

    /**
     * What the registrations pass to Spring's own origin check: every origin, because the real check
     * already ran here. Never use it without registering this interceptor first.
     */
    public static final String CHECKED_HERE = "*";

    private final AllowedOrigins allowedOrigins;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (WebUtils.isSameOrigin(request)) {
            return true;
        }
        String origin = request.getHeaders().getOrigin();
        if (allowedOrigins.isAllowed(origin)) {
            return true;
        }
        log.debug("WebSocket handshake rejected: origin {} is not allowed", origin);
        response.setStatusCode(HttpStatus.FORBIDDEN);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
