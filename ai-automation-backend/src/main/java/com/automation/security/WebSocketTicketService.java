package com.automation.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short-lived, single-use tickets for the WebSocket handshake.
 *
 * <p>Neither SockJS nor a raw browser WebSocket can send its own headers during the handshake, so
 * the only way to authenticate it is the URL. Putting the API key there leaks it into browser
 * history and proxy logs; a ticket that is valid for seconds and dies on first use does not.
 *
 * <p>Purely in memory (no DB — the single SQLite connection must not be touched on a handshake) and
 * therefore instance-local: tickets do not survive a restart, which is exactly the intended
 * lifetime. Expired entries are swept on every {@link #issue()} and {@link #consume(String)}, so no
 * scheduler is needed and the map stays bounded by the issue rate within one TTL window.
 */
@Service
public class WebSocketTicketService {

    /** 32 random bytes — a ticket must be unguessable within its (short) lifetime. */
    private static final int TICKET_BYTES = 32;

    private final SecureRandom random = new SecureRandom();
    private final Map<String, Instant> tickets = new ConcurrentHashMap<>();
    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public WebSocketTicketService(
            @Value("${automation.websocket.ticket-ttl-seconds:30}") long ttlSeconds) {
        this(Clock.systemUTC(), ttlSeconds);
    }

    WebSocketTicketService(Clock clock, long ttlSeconds) {
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds > 0 ? ttlSeconds : 30);
    }

    /** Issues a fresh ticket. The caller must already be authenticated by the API key header. */
    public String issue() {
        purgeExpired();
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        tickets.put(ticket, clock.instant().plus(ttl));
        return ticket;
    }

    /**
     * Redeems a ticket. Returns true exactly once per issued ticket, and only before it expires —
     * a replayed or forged ticket is indistinguishable from an unknown one.
     */
    public boolean consume(String ticket) {
        purgeExpired();
        if (ticket == null || ticket.isBlank()) {
            return false;
        }
        Instant expiresAt = tickets.remove(ticket);
        return expiresAt != null && expiresAt.isAfter(clock.instant());
    }

    /** Lifetime in seconds, handed to the client so it knows how long it may hold the ticket. */
    public long getTtlSeconds() {
        return ttl.toSeconds();
    }

    /** Visible for tests: how many tickets are still stored (deliberately without sweeping first). */
    int activeTicketCount() {
        return tickets.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
    }
}
