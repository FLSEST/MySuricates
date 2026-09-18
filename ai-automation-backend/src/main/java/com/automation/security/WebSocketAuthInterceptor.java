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

import java.util.Map;

/**
 * Authenticates every WebSocket handshake — STOMP/SockJS on {@code /ws} and the raw run terminal on
 * {@code /ws/terminal/**} — with a single-use ticket from {@link WebSocketTicketService}.
 *
 * <p>The API key is deliberately <em>not</em> accepted as a query parameter any more: handshake URLs
 * end up in browser history and proxy logs. Clients call {@code POST /api/v1/ws-ticket} with the key
 * in the header and append the returned ticket instead.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WebSocketAuthInterceptor implements HandshakeInterceptor {

    private final ApiKeyService apiKeyService;
    private final WebSocketTicketService ticketService;

    @Value("${automation.security.enabled:true}")
    private boolean securityEnabled;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                   WebSocketHandler wsHandler, Map<String, Object> attributes) {
        String effectiveApiKey = apiKeyService.getEffectiveApiKey();

        if (!securityEnabled || effectiveApiKey.isBlank()) {
            return true;
        }

        String ticket = null;
        boolean legacyApiKeyParam = false;
        if (request instanceof ServletServerHttpRequest servletRequest) {
            ticket = servletRequest.getServletRequest().getParameter("ticket");
            legacyApiKeyParam = servletRequest.getServletRequest().getParameter("apiKey") != null;
        }

        // A ticket is only ever handed out to a caller that presented the key in the header, so
        // redeeming one is proof of authentication — and it works exactly once.
        if (ticketService.consume(ticket)) {
            return true;
        }

        if (legacyApiKeyParam) {
            log.warn("WebSocket handshake rejected: apiKey query parameter is no longer accepted "
                    + "(use POST /api/v1/ws-ticket), from {}", request.getRemoteAddress());
        } else {
            log.warn("WebSocket handshake rejected: missing, expired or already used ticket from {}",
                    request.getRemoteAddress());
        }
        response.setStatusCode(HttpStatus.UNAUTHORIZED);
        return false;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                               WebSocketHandler wsHandler, Exception exception) {
        // no-op
    }
}
