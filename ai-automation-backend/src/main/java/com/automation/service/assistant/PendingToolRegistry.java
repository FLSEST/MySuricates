package com.automation.service.assistant;

import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store for {@link PendingTool}s awaiting user confirmation.
 * <p>
 * Keyed by UUID. Entries auto-expire after {@link #TTL} — the scheduled cleanup job removes
 * them, and {@link #take(String)} filters out expired entries on read so a stale confirm-click
 * never fires a tool.
 *
 * <p>Not persisted: a backend restart drops all pending tools. That's intentional — the user
 * should re-ask rather than confirm an hours-old proposal whose context is gone.
 */
@Component
@Slf4j
public class PendingToolRegistry {

    public static final Duration TTL = Duration.ofMinutes(30);

    private final ConcurrentHashMap<String, PendingTool> pending = new ConcurrentHashMap<>();

    /** Registers a new pending tool and returns it with a generated ID + expiry. */
    public PendingTool register(String type, String description, String payloadJson) {
        String id = UUID.randomUUID().toString();
        PendingTool tool = new PendingTool(id, type, description, payloadJson, Instant.now().plus(TTL));
        pending.put(id, tool);
        return tool;
    }

    /**
     * Atomically removes and returns the pending tool with the given ID — the intended use is
     * "fetch and execute exactly once". Returns empty if not found or expired.
     */
    public Optional<PendingTool> take(String id) {
        PendingTool tool = pending.remove(id);
        if (tool == null) return Optional.empty();
        if (tool.isExpired()) {
            log.debug("Pending tool {} expired at {}", id, tool.expiresAt());
            return Optional.empty();
        }
        return Optional.of(tool);
    }

    /** Peeks without removing — used for read-only inspection (e.g. describe in API). */
    public Optional<PendingTool> peek(String id) {
        PendingTool tool = pending.get(id);
        if (tool == null || tool.isExpired()) return Optional.empty();
        return Optional.of(tool);
    }

    @Scheduled(fixedDelay = 5 * 60 * 1000L)
    void cleanupExpired() {
        int before = pending.size();
        pending.entrySet().removeIf(e -> e.getValue().isExpired());
        int removed = before - pending.size();
        if (removed > 0) log.debug("Pending-tool cleanup: removed {} expired entries", removed);
    }

    /** Exposed for tests. */
    int size() {
        return pending.size();
    }
}
