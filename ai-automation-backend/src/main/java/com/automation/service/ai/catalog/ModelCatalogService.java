package com.automation.service.ai.catalog;

import com.automation.concurrency.SingleFlight;
import com.automation.config.ModelCatalogConfig;
import com.automation.exception.ResourceNotFoundException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Serves the per-provider model catalog with an in-memory TTL cache.
 *
 * <p>Probing shells out to host CLIs (up to {@code probeTimeoutSeconds}), so the cache keeps
 * the UI from spawning a process on every render. Failed probes are cached too, for a shorter
 * time, so a missing CLI does not turn every settings page load into a retry storm.
 * No DB access, no {@code @Transactional}.
 */
@Service
@Slf4j
public class ModelCatalogService {

    /** Failed probes are retried no later than this, regardless of the configured TTL. */
    static final long FAILURE_TTL_MS = 60_000;

    private final Map<String, ModelCatalogSource> sources;
    private final Map<String, CachedCatalog> cache = new ConcurrentHashMap<>();
    /**
     * At most one probe per provider at a time; concurrent requests share it. Not a
     * {@code synchronized} block: requests run on virtual threads and the pi probe is a process
     * of up to {@code probeTimeoutSeconds}, during which a monitor would pin the carrier of the
     * prober and of every waiting request (TODO a7294cf2).
     */
    private final SingleFlight<String, ModelCatalog> probes = new SingleFlight<>();
    private volatile long ttlMs;

    private record CachedCatalog(ModelCatalog catalog, long timestamp) {
        boolean isExpired(long ttlMs) {
            long effectiveTtl = catalog.available() ? ttlMs : Math.min(ttlMs, FAILURE_TTL_MS);
            return System.currentTimeMillis() - timestamp >= effectiveTtl;
        }
    }

    public ModelCatalogService(List<ModelCatalogSource> sources, ModelCatalogConfig config) {
        this.sources = sources.stream().collect(Collectors.toMap(
                s -> normalize(s.providerId()), Function.identity(),
                (a, b) -> { throw new IllegalStateException("Duplicate ModelCatalogSource for " + a.providerId()); },
                LinkedHashMap::new));
        this.ttlMs = config.getTtlSeconds() * 1000L;
    }

    /** Provider ids that have a catalog source, in registration order. */
    public List<String> providerIds() {
        return List.copyOf(sources.keySet());
    }

    public boolean knows(String providerId) {
        return providerId != null && sources.containsKey(normalize(providerId));
    }

    /**
     * @param providerId   registry id, case-insensitive
     * @param forceRefresh bypass the cache and probe the host now; a probe for the provider that
     *                     is already running counts as "now", its result is shared
     * @throws ResourceNotFoundException when no source exists for the provider (→ HTTP 404)
     */
    public ModelCatalog getCatalog(String providerId, boolean forceRefresh) {
        String id = normalize(providerId);
        ModelCatalogSource source = sources.get(id);
        if (source == null) {
            throw new ResourceNotFoundException("AiProvider", providerId);
        }
        CachedCatalog cached = cache.get(id);
        if (!forceRefresh && cached != null && !cached.isExpired(ttlMs)) {
            return cached.catalog();
        }
        return probes.run(id, () -> {
            // A probe that finished between the check above and here has just filled the cache.
            CachedCatalog current = cache.get(id);
            if (!forceRefresh && current != null && !current.isExpired(ttlMs)) {
                return current.catalog();
            }
            ModelCatalog fresh = probe(source);
            cache.put(id, new CachedCatalog(fresh, System.currentTimeMillis()));
            return fresh;
        });
    }

    /** Visible for tests: how many providers are being probed right now. */
    int probesInFlight() {
        return probes.inFlight();
    }

    private ModelCatalog probe(ModelCatalogSource source) {
        try {
            ModelCatalog catalog = source.fetch();
            if (catalog == null) {
                return ModelCatalog.unavailable(source.providerId(), "unknown", "source returned null");
            }
            log.debug("Model catalog for {} refreshed from {}: {} models (available={})",
                    catalog.providerId(), catalog.source(), catalog.models().size(), catalog.available());
            return catalog;
        } catch (RuntimeException e) {
            // Sources promise not to throw; guard anyway so one bad probe cannot break the endpoint.
            log.warn("Model catalog source {} threw", source.providerId(), e);
            return ModelCatalog.unavailable(source.providerId(), "unknown", e.toString());
        }
    }

    /** Runtime setter for the execution setting {@code model-catalog-ttl-seconds}. */
    public void setTtlSeconds(int seconds) {
        this.ttlMs = seconds * 1000L;
    }

    public int getTtlSeconds() {
        return (int) (ttlMs / 1000L);
    }

    public void invalidate() {
        cache.clear();
    }

    private static String normalize(String providerId) {
        return providerId == null ? "" : providerId.trim().toUpperCase(Locale.ROOT);
    }
}
