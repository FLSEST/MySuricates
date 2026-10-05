package com.automation.controller;

import com.automation.security.WebSocketTicketService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Hands out short-lived, single-use tickets for the WebSocket handshake.
 *
 * <p>The API key travels in the header here (like every other endpoint) — the ticket is what ends up
 * in the handshake URL, because neither SockJS nor a raw browser WebSocket can set headers.
 * Live logs and the assistant are core features, so the endpoint carries no module gate
 * (pinned in {@code ControllerModuleGateTest}); the ordinary API rate limit applies to it.
 */
@RestController
@RequestMapping("/api/v1/ws-ticket")
@RequiredArgsConstructor
public class WebSocketTicketController {

    private final WebSocketTicketService ticketService;

    /** Issues a single-use ticket for the WebSocket handshake URL; it expires after {@code expiresInSeconds}. */
    @PostMapping
    public WsTicketResponse issue() {
        return new WsTicketResponse(ticketService.issue(), ticketService.getTtlSeconds());
    }

    public record WsTicketResponse(String ticket, long expiresInSeconds) {}
}
