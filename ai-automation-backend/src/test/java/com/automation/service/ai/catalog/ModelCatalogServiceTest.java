package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.automation.exception.ResourceNotFoundException;
import com.automation.testsupport.ParkedThreads;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelCatalogServiceTest {

    /** Counts probes; returns an available catalog unless {@code fail} is set. */
    private static final class CountingSource implements ModelCatalogSource {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;
        private final String id;

        CountingSource(String id) {
            this.id = id;
        }

        @Override
        public String providerId() {
            return id;
        }

        @Override
        public ModelCatalog fetch() {
            calls.incrementAndGet();
            return fail
                    ? ModelCatalog.unavailable(id, "test", "boom")
                    : ModelCatalog.available(id, "test", Instant.now(), List.of(ModelCatalog.ModelOption.of("m1", "M1")));
        }
    }

    private static ModelCatalogService service(int ttlSeconds, ModelCatalogSource... sources) {
        ModelCatalogConfig config = new ModelCatalogConfig();
        config.setTtlSeconds(ttlSeconds);
        return new ModelCatalogService(List.of(sources), config);
    }

    @Test
    void getCatalog_cachesWithinTtl() {
        CountingSource src = new CountingSource("PI");
        ModelCatalogService service = service(600, src);

        ModelCatalog first = service.getCatalog("PI", false);
        ModelCatalog second = service.getCatalog("pi", false);

        assertThat(first).isSameAs(second);
        assertThat(src.calls.get()).isEqualTo(1);
    }

    @Test
    void getCatalog_forceRefresh_bypassesCache() {
        CountingSource src = new CountingSource("CODEX");
        ModelCatalogService service = service(600, src);

        service.getCatalog("CODEX", false);
        service.getCatalog("CODEX", true);

        assertThat(src.calls.get()).isEqualTo(2);
    }

    @Test
    void getCatalog_unknownProvider_throwsNotFound() {
        ModelCatalogService service = service(600, new CountingSource("CLAUDE"));

        assertThatThrownBy(() -> service.getCatalog("GEMINI", false))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("GEMINI");
        assertThat(service.knows("claude")).isTrue();
        assertThat(service.knows("GEMINI")).isFalse();
        assertThat(service.knows(null)).isFalse();
    }

    @Test
    void setTtlSeconds_toZero_expiresImmediately() {
        CountingSource src = new CountingSource("PI");
        ModelCatalogService service = service(600, src);
        service.getCatalog("PI", false);

        service.setTtlSeconds(0);
        service.getCatalog("PI", false);

        assertThat(service.getTtlSeconds()).isZero();
        assertThat(src.calls.get()).isEqualTo(2);
    }

    @Test
    void invalidate_dropsAllCachedCatalogs() {
        CountingSource src = new CountingSource("PI");
        ModelCatalogService service = service(600, src);
        service.getCatalog("PI", false);

        service.invalidate();
        service.getCatalog("PI", false);

        assertThat(src.calls.get()).isEqualTo(2);
    }

    @Test
    void failedProbe_isCachedToo_soUiDoesNotRetryOnEveryRender() {
        CountingSource src = new CountingSource("PI");
        src.fail = true;
        ModelCatalogService service = service(600, src);

        ModelCatalog a = service.getCatalog("PI", false);
        ModelCatalog b = service.getCatalog("PI", false);

        assertThat(a.available()).isFalse();
        assertThat(b).isSameAs(a);
        assertThat(src.calls.get()).isEqualTo(1);
    }

    @Test
    void throwingSource_isReportedAsUnavailable_notPropagated() {
        ModelCatalogSource bad = new ModelCatalogSource() {
            @Override public String providerId() { return "PI"; }
            @Override public ModelCatalog fetch() { throw new IllegalStateException("kaboom"); }
        };
        ModelCatalogService service = service(600, bad);

        ModelCatalog catalog = service.getCatalog("PI", false);

        assertThat(catalog.available()).isFalse();
        assertThat(catalog.reason()).contains("kaboom");
    }

    /** Like {@link CountingSource}, but each probe waits until the test releases it. */
    private static final class BlockingSource implements ModelCatalogSource {
        final AtomicInteger calls = new AtomicInteger();
        final CountDownLatch inside = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        private final ModelCatalog result;

        BlockingSource(ModelCatalog result) {
            this.result = result;
        }

        @Override
        public String providerId() {
            return result.providerId();
        }

        @Override
        public ModelCatalog fetch() {
            calls.incrementAndGet();
            inside.countDown();
            ParkedThreads.await(release);
            return result;
        }
    }

    /**
     * Starts a request that probes, then a second request for the same provider while the probe
     * runs; the second must wait parked (not blocked on a monitor: requests run on virtual
     * threads) and share the probe. Returns both results.
     */
    private static ModelCatalog[] twoConcurrentRequests(ModelCatalogService service, BlockingSource src,
                                                        boolean secondForcesRefresh) throws Exception {
        AtomicReference<ModelCatalog> first = new AtomicReference<>();
        AtomicReference<ModelCatalog> second = new AtomicReference<>();
        Thread t1 = ParkedThreads.start(() -> first.set(service.getCatalog("PI", false)));
        assertThat(src.inside.await(5, TimeUnit.SECONDS)).as("first request probing").isTrue();
        Thread t2 = ParkedThreads.start(() -> second.set(service.getCatalog("pi", secondForcesRefresh)));
        ParkedThreads.awaitParked(t2);

        src.release.countDown();
        ParkedThreads.join(t1);
        ParkedThreads.join(t2);
        return new ModelCatalog[] {first.get(), second.get()};
    }

    @Test
    void concurrentRequests_shareOneProbe() throws Exception {
        BlockingSource src = new BlockingSource(
                ModelCatalog.available("PI", "test", Instant.now(), List.of(ModelCatalog.ModelOption.of("m1", "M1"))));
        ModelCatalogService service = service(600, src);

        ModelCatalog[] results = twoConcurrentRequests(service, src, false);

        assertThat(src.calls.get()).as("one pi --list-models for both requests").isEqualTo(1);
        assertThat(results[1]).isSameAs(results[0]);
        assertThat(results[0].available()).isTrue();
        assertThat(service.probesInFlight()).as("no per-provider entry left behind").isZero();
        assertThat(service.getCatalog("PI", false)).as("the shared probe filled the cache").isSameAs(results[0]);
        assertThat(src.calls.get()).isEqualTo(1);
    }

    @Test
    void concurrentRequests_failedProbe_isSharedToo() throws Exception {
        BlockingSource src = new BlockingSource(ModelCatalog.unavailable("PI", "test", "pi not installed"));
        ModelCatalogService service = service(600, src);

        ModelCatalog[] results = twoConcurrentRequests(service, src, false);

        assertThat(src.calls.get()).isEqualTo(1);
        assertThat(results[0].available()).isFalse();
        assertThat(results[1]).isSameAs(results[0]);
        assertThat(service.probesInFlight()).isZero();
    }

    /** A refresh that arrives while a probe runs takes that probe's result instead of starting a second one. */
    @Test
    void forceRefresh_whileAProbeRuns_joinsIt() throws Exception {
        BlockingSource src = new BlockingSource(
                ModelCatalog.available("PI", "test", Instant.now(), List.of(ModelCatalog.ModelOption.of("m1", "M1"))));
        ModelCatalogService service = service(600, src);

        ModelCatalog[] results = twoConcurrentRequests(service, src, true);

        assertThat(src.calls.get()).isEqualTo(1);
        assertThat(results[1]).isSameAs(results[0]);
        assertThat(service.probesInFlight()).isZero();
    }

    @Test
    void sourceThrowingAnError_reachesTheCaller_andTheNextRequestProbesAgain() {
        AtomicInteger calls = new AtomicInteger();
        ModelCatalogSource dying = new ModelCatalogSource() {
            @Override public String providerId() { return "PI"; }
            @Override public ModelCatalog fetch() {
                if (calls.incrementAndGet() == 1) {
                    throw new LinkageError("broken");
                }
                return ModelCatalog.unavailable("PI", "test", "still broken");
            }
        };
        ModelCatalogService service = service(600, dying);

        assertThatThrownBy(() -> service.getCatalog("PI", false)).isInstanceOf(LinkageError.class);
        assertThat(service.probesInFlight()).isZero();

        assertThat(service.getCatalog("PI", false).reason()).isEqualTo("still broken");
        assertThat(calls.get()).isEqualTo(2);
    }

    @Test
    void providerIds_listsSourcesInRegistrationOrder() {
        ModelCatalogService service = service(600,
                new CountingSource("CLAUDE"), new CountingSource("PI"), new CountingSource("CODEX"));

        assertThat(service.providerIds()).containsExactly("CLAUDE", "PI", "CODEX");
    }
}
