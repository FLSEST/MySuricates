package com.automation.security;

import com.automation.service.system.ApiKeyService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final ApiKeyService apiKeyService;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String effectiveApiKey = apiKeyService.getEffectiveApiKey();

        if (!securityEnabled || effectiveApiKey.isBlank()) {
            return true;
        }

        String apiKey = null;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            apiKey = servletRequest.getServletRequest().getParameter("apiKey");
        }

        if (apiKey != null && constantTimeEquals(apiKey, effectiveApiKey)) {
            return true;
        }

        log.warn("WebSocket handshake rejected: invalid or missing API key from {}",
                request.getRemoteAddress());
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }

    private boolean constantTimeEquals(String a, String b) {
        return MessageDigest.isEqual(
                a.getBytes(StandardCharsets.UTF_8),
                b.getBytes(StandardCharsets.UTF_8));
    }
}
