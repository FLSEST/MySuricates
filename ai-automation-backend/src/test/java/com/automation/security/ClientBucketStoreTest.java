package com.automation.security;

import org.junit.jupiter.api.Test;

import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class ClientBucketStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    /** Minimal bucket with a fixed window start. */
    private record Fixed(long windowStart) implements ClientBucketStore.Windowed {}

    @Test
    void sameClientGetsTheSameBucket() {
        ClientBucketStore<Fixed> store = new ClientBucketStore<>(100, T0);
        Fixed first = store.get("a", () -> new Fixed(T0), T0);

        assertThat(store.get("a", () -> new Fixed(T0 + 1), T0 + 1)).isSameAs(first);
        assertThat(store.size()).isEqualTo(1);
    }

    @Test
    void expiredBucketsAreSweptOncePerWindow() {
        ClientBucketStore<Fixed> store = new ClientBucketStore<>(100, T0);
        IntStream.range(0, 50).forEach(i -> store.get("old-" + i, () -> new Fixed(T0), T0));
        assertThat(store.size()).isEqualTo(50);

        long later = T0 + ClientBucketStore.WINDOW_MS + 1;
        store.get("fresh", () -> new Fixed(later), later);

        assertThat(store.size()).isEqualTo(1);
        assertThat(store.peek("fresh")).isNotNull();
    }

    @Test
    void neverGrowsBeyondTheCapEvenWithinOneWindow() {
        int cap = 100;
        ClientBucketStore<Fixed> store = new ClientBucketStore<>(cap, T0);

        // one new client id per request — what a spoofed X-Forwarded-For used to produce
        for (int i = 0; i < 10_000; i++) {
            long now = T0 + i;
            store.get("client-" + i, () -> new Fixed(now), now);
            assertThat(store.size()).isLessThanOrEqualTo(cap + 1);
        }
        // the newest client survives, the oldest windows went first
        assertThat(store.peek("client-9999")).isNotNull();
        assertThat(store.peek("client-0")).isNull();
    }

    @Test
    void activeBucketsSurviveASweep() {
        ClientBucketStore<Fixed> store = new ClientBucketStore<>(100, T0);
        long later = T0 + ClientBucketStore.WINDOW_MS + 1;
        store.get("active", () -> new Fixed(later - 1_000), later - 1_000);
        store.get("stale", () -> new Fixed(T0 - 5_000), T0);

        store.get("other", () -> new Fixed(later), later);

        assertThat(store.peek("active")).isNotNull();
        assertThat(store.peek("stale")).isNull();
    }
}
