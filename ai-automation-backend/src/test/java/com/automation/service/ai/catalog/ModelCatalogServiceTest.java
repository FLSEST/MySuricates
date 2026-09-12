package com.automation.service.ai.catalog;

import com.automation.config.ModelCatalogConfig;
import com.automation.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

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

    @Test
    void providerIds_listsSourcesInRegistrationOrder() {
        ModelCatalogService service = service(600,
                new CountingSource("CLAUDE"), new CountingSource("PI"), new CountingSource("CODEX"));

        assertThat(service.providerIds()).containsExactly("CLAUDE", "PI", "CODEX");
    }
}
