package com.automation.service.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for the CSRF {@code state} of a running OAuth2 authorization.
 *
 * <p>Entries expire after {@link #TTL} and the map is capped at {@link #MAX_PENDING}: an
 * abandoned consent used to leave its state behind forever, so every {@code GET
 * /api/v1/integrations/{id}/authorize-url} call grew the map by one entry that nothing ever
 * removed. {@link #consume(String)} and {@link #peek(String)} additionally filter expired
 * entries on read, so a stale callback can never complete a hours-old authorization.
 *
 * <p>At capacity {@link #register} fails instead of evicting the oldest entry — evicting would
 * silently break a consent that is still in the user's browser.
 *
 * <p>Not persisted: a backend restart drops all pending authorizations. That is intentional —
 * the redirect would come back to a process that never issued the state.
 */
@Component
@Slf4j
public class OAuthStateRegistry {

    public static final Duration TTL = Duration.ofMinutes(10);
    static final int MAX_PENDING = 1000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    /** Binds provider and notification origin to the server-generated OAuth state. */
    public record PendingAuthorization(String providerId, String callbackOrigin, Instant expiresAt) {
        public boolean isExpired() {
            return expiresAt != null && Instant.now().isAfter(expiresAt);
        }
    }

    private final ConcurrentHashMap<String, PendingAuthorization> pending = new ConcurrentHashMap<>();

    /**
     * Generates a fresh state, binds it to the provider + origin and returns it.
     *
     * <p>Synchronized: purge → capacity check → put must be one step, otherwise concurrent
     * registrations can push the map past {@link #MAX_PENDING}. Registrations are rare (one per
     * OAuth connect click), so a monitor costs nothing.
     */
    public synchronized String register(String providerId, String callbackOrigin) {
        cleanupExpired();
        if (pending.size() >= MAX_PENDING) {
            throw new IllegalStateException("Too many pending OAuth authorizations — retry later");
        }
        String state = generateState();
        pending.put(state, new PendingAuthorization(providerId, callbackOrigin, Instant.now().plus(TTL)));
        return state;
    }

    /** Removes and returns the authorization — one state completes exactly one callback. */
    public Optional<PendingAuthorization> consume(String state) {
        PendingAuthorization authorization = pending.remove(state);
        if (authorization == null) return Optional.empty();
        if (authorization.isExpired()) {
            log.debug("OAuth state for {} expired at {}", authorization.providerId(), authorization.expiresAt());
            return Optional.empty();
        }
        return Optional.of(authorization);
    }

    /** Reads without consuming — the callback origin is needed before the state is spent. */
    public Optional<PendingAuthorization> peek(String state) {
        PendingAuthorization authorization = pending.get(state);
        if (authorization == null) return Optional.empty();
        if (authorization.isExpired()) {
            pending.remove(state, authorization);   // expired entries never become valid again
            return Optional.empty();
        }
        return Optional.of(authorization);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void cleanupExpired() {
        int before = pending.size();
        pending.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - pending.size();
        if (removed > 0) log.debug("OAuth state cleanup: removed {} expired entries", removed);
    }

    private static String generateState() {
        byte[] bytes = new byte[32];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** Exposed for tests. */
    int size() {
        return pending.size();
    }
}
