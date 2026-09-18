package com.automation.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

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
 * scheduler is needed.
 *
 * <p>On top of the TTL sweep the store is hard-capped at {@link #MAX_OPEN_TICKETS} entries: issuing
 * requires a valid API key and is rate-limited, but a caller that holds the key must not be able to
 * grow the map without bound. Past the cap the <em>oldest</em> ticket is dropped (insertion-ordered
 * {@link LinkedHashMap} instead of a {@code ConcurrentHashMap}, because a FIFO cap needs the order);
 * a client whose ticket was evicted simply gets a fresh one on its next attempt. Access is
 * serialised with a plain monitor — every operation is a handful of map calls with no I/O.
 */
@Service
public class WebSocketTicketService {

    /** 32 random bytes — a ticket must be unguessable within its (short) lifetime. */
    private static final int TICKET_BYTES = 32;

    /** Single source of truth for the lifetime: {@code @Value} default and constructor fallback. */
    static final long DEFAULT_TTL_SECONDS = 30;

    /**
     * Upper bound on outstanding tickets. Far above any real usage (one ticket per connect attempt,
     * alive for seconds), low enough that the map cannot become a memory problem.
     */
    static final int MAX_OPEN_TICKETS = 1000;

    private final SecureRandom random = new SecureRandom();

    /** Insertion-ordered so the cap can evict the oldest entry; guarded by {@code this}. */
    private final Map<String, Instant> tickets = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Instant> eldest) {
            return size() > MAX_OPEN_TICKETS;
        }
    };

    private final Clock clock;
    private final Duration ttl;

    @Autowired
    public WebSocketTicketService(
            @Value("${automation.websocket.ticket-ttl-seconds:" + DEFAULT_TTL_SECONDS + "}") long ttlSeconds) {
        this(Clock.systemUTC(), ttlSeconds);
    }

    WebSocketTicketService(Clock clock, long ttlSeconds) {
        this.clock = clock;
        this.ttl = Duration.ofSeconds(ttlSeconds > 0 ? ttlSeconds : DEFAULT_TTL_SECONDS);
    }

    /** Issues a fresh ticket. The caller must already be authenticated by the API key header. */
    public synchronized String issue() {
        purgeExpired();
        byte[] bytes = new byte[TICKET_BYTES];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        // Past MAX_OPEN_TICKETS the map drops its eldest entry itself (removeEldestEntry)
        tickets.put(ticket, clock.instant().plus(ttl));
        return ticket;
    }

    /**
     * Redeems a ticket. Returns true exactly once per issued ticket, and only before it expires —
     * a replayed or forged ticket is indistinguishable from an unknown one.
     */
    public synchronized boolean consume(String ticket) {
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
    synchronized int activeTicketCount() {
        return tickets.size();
    }

    private void purgeExpired() {
        Instant now = clock.instant();
        tickets.values().removeIf(expiresAt -> !expiresAt.isAfter(now));
    }
}
