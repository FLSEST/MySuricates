package com.automation.service.integration;

import com.automation.service.integration.OAuthStateRegistry.PendingAuthorization;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pending OAuth states used to live in an unbounded map that only ever shrank when a callback
 * arrived — an abandoned consent (or a scripted authorize-URL loop) leaked an entry per call.
 * Entries now carry a TTL and the map is capped.
 */
class OAuthStateRegistryTest {

    private final OAuthStateRegistry registry = new OAuthStateRegistry();

    @Test
    void register_bindsProviderAndOriginToAFreshState() {
        String state = registry.register("google-calendar", "https://localhost:3000");

        assertThat(state).isNotBlank();
        assertThat(registry.register("google-calendar", "https://localhost:3000")).isNotEqualTo(state);

        PendingAuthorization pending = registry.peek(state).orElseThrow();
        assertThat(pending.providerId()).isEqualTo("google-calendar");
        assertThat(pending.callbackOrigin()).isEqualTo("https://localhost:3000");
        assertThat(pending.isExpired()).isFalse();
        assertThat(pending.expiresAt()).isAfter(Instant.now().plus(OAuthStateRegistry.TTL).minusSeconds(10));
    }

    @Test
    void consume_removesEntryAfterFirstUse() {
        String state = registry.register("jira", null);

        assertThat(registry.consume(state)).isPresent();
        assertThat(registry.consume(state)).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void consume_expiredState_returnsEmptyAndRemoves() {
        String state = injectExpired("google-calendar");

        assertThat(registry.consume(state)).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void peek_expiredState_returnsEmpty() {
        String state = injectExpired("google-calendar");

        assertThat(registry.peek(state)).isEmpty();
    }

    @Test
    void register_purgesExpiredBeforeCapacityCheck() {
        for (int i = 0; i < OAuthStateRegistry.MAX_PENDING; i++) {
            injectExpired("google-calendar");
        }
        assertThat(registry.size()).isEqualTo(OAuthStateRegistry.MAX_PENDING);

        String state = registry.register("google-calendar", null);

        assertThat(registry.peek(state)).isPresent();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void register_atCapacity_throws() {
        for (int i = 0; i < OAuthStateRegistry.MAX_PENDING; i++) {
            registry.register("google-calendar", null);
        }

        // Deliberately no evict-oldest: that would silently break a consent that is still running.
        assertThatThrownBy(() -> registry.register("google-calendar", null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Too many pending OAuth authorizations");
        assertThat(registry.size()).isEqualTo(OAuthStateRegistry.MAX_PENDING);
    }

    @Test
    void cleanupExpired_removesOnlyExpired() {
        String live = registry.register("google-calendar", null);
        String expired = injectExpired("jira");

        registry.cleanupExpired();

        assertThat(registry.peek(live)).isPresent();
        assertThat(registry.peek(expired)).isEmpty();
        assertThat(registry.size()).isEqualTo(1);
    }

    @SuppressWarnings("unchecked")
    private String injectExpired(String providerId) {
        String state = "expired-" + UUID.randomUUID();
        ConcurrentHashMap<String, PendingAuthorization> pending =
                (ConcurrentHashMap<String, PendingAuthorization>) ReflectionTestUtils.getField(registry, "pending");
        pending.put(state, new PendingAuthorization(providerId, null, Instant.now().minusSeconds(60)));
        return state;
    }
}
