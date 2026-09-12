package com.automation.service.ai.catalog;

/**
 * Discovers the models one AI provider offers on this host.
 *
 * <p>One bean per provider id; {@link ModelCatalogService} aggregates and caches them.
 * Kept separate from {@link com.automation.service.ai.AiProvider} so the execution
 * interface and its capabilities record stay untouched.
 */
public interface ModelCatalogSource {

    /** Registry id of the provider this source describes, e.g. {@code CLAUDE}. */
    String providerId();

    /**
     * Probes the host. Must never throw: a failed probe is reported as
     * {@link ModelCatalog#unavailable(String, String, String)}.
     */
    ModelCatalog fetch();
}
