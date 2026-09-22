package com.automation.security;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-client fixed-window buckets that do not grow without bound (TODO ba6a91a6).
 *
 * <p>Swept on access, no scheduler: at most once per window, and whenever the map exceeds
 * {@code maxEntries}, buckets whose window has run out are dropped — they would reset on their
 * next use anyway, so nothing is lost. If that is not enough (more distinct clients active within
 * one window than the cap), the buckets with the oldest window go first: they are the closest to
 * resetting on their own.
 */
final class ClientBucketStore<B extends ClientBucketStore.Windowed> {

    /** A bucket that knows when its current window started (epoch ms). */
    interface Windowed {
        long windowStart();
    }

    static final long WINDOW_MS = 60_000;
    static final int DEFAULT_MAX_ENTRIES = 10_000;

    private final Map<String, B> buckets = new ConcurrentHashMap<>();
    private final int maxEntries;
    private final AtomicLong lastSweep;

    ClientBucketStore() {
        this(DEFAULT_MAX_ENTRIES, System.currentTimeMillis());
    }

    ClientBucketStore(int maxEntries, long now) {
        if (maxEntries < 1) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }
        this.maxEntries = maxEntries;
        this.lastSweep = new AtomicLong(now);
    }

    B get(String clientId, Supplier<B> factory, long now) {
        B bucket = buckets.computeIfAbsent(clientId, k -> factory.get());
        maybeSweep(now, clientId);
        return bucket;
    }

    int size() {
        return buckets.size();
    }

    /** The bucket of a client, if one exists — for tests. */
    B peek(String clientId) {
        return buckets.get(clientId);
    }

    private void maybeSweep(long now, String keep) {
        long last = lastSweep.get();
        boolean due = now - last >= WINDOW_MS;
        boolean overCap = buckets.size() > maxEntries;
        if (!due && !overCap) {
            return;
        }
        // One sweeper at a time; a lost race just means another thread is already at it.
        if (!lastSweep.compareAndSet(last, now)) {
            return;
        }
        buckets.entrySet().removeIf(e -> !e.getKey().equals(keep)
                && now - e.getValue().windowStart() > WINDOW_MS);
        if (buckets.size() <= maxEntries) {
            return;
        }
        List<Map.Entry<String, B>> oldestFirst = buckets.entrySet().stream()
                .filter(e -> !e.getKey().equals(keep))
                .sorted(Comparator.comparingLong(e -> e.getValue().windowStart()))
                .toList();
        // Evict down to 90 % so the next request does not sort the map again right away.
        int target = Math.max(1, maxEntries - maxEntries / 10);
        for (Map.Entry<String, B> e : oldestFirst) {
            if (buckets.size() <= target) {
                break;
            }
            buckets.remove(e.getKey(), e.getValue());
        }
    }
}
