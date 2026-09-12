package com.automation.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Configuration for the host-discovered AI model catalog
 * ({@link com.automation.service.ai.catalog.ModelCatalogService}).
 *
 * <p>The catalog is probed from the host CLIs (pi, codex) and their local caches — never
 * from a network API. The TTL is runtime-tunable via the execution setting
 * {@code model-catalog-ttl-seconds}.
 */
@Configuration
@ConfigurationProperties(prefix = "automation.model-catalog")
@Getter
@Setter
public class ModelCatalogConfig {

    /** How long a probed catalog is served from memory before the host is asked again. */
    private int ttlSeconds = 600;

    /** Wall-clock limit for a single CLI probe such as {@code pi --list-models}. */
    private int probeTimeoutSeconds = 15;

    /** Directory holding codex's runtime files; {@code models_cache.json} is read from here. */
    private String codexHome = System.getProperty("user.home") + "/.codex";

    /**
     * Claude Code has no model listing command. These aliases are accepted by {@code claude --model}
     * and always resolve to the newest model of their family, so they never go stale.
     */
    private List<String> claudeAliases = List.of("fable", "opus", "sonnet", "haiku");
}
