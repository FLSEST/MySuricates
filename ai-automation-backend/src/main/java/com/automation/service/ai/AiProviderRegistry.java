package com.automation.service.ai;

import com.automation.config.AiProviderConfig;
import com.automation.repository.AppSettingsRepository;
import com.automation.service.system.AppSettingChangedEvent;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Central registry for AI providers.
 * Resolves a provider by workflow-level override or global default.
 */
@Service
@Slf4j
public class AiProviderRegistry {

    private final Map<String, AiProvider> providers;
    private final AiProviderConfig config;
    private final AppSettingsRepository appSettingsRepository;

    /** Cached default provider ID — loaded at startup, refreshed on settings change. Never read from DB during a request. */
    private volatile String cachedDefaultProviderId = null;

    public AiProviderRegistry(List<AiProvider> providerList, AiProviderConfig config,
                               AppSettingsRepository appSettingsRepository) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(AiProvider::getId, Function.identity()));
        this.config = config;
        this.appSettingsRepository = appSettingsRepository;
        log.info("AI providers registered: {} (default: {})",
                providers.keySet(), config.getDefaultProvider());
    }

    @PostConstruct
    public void initCache() {
        cachedDefaultProviderId = appSettingsRepository.get("default-ai-provider")
                .filter(v -> !v.isBlank() && providers.containsKey(v.toUpperCase(Locale.ROOT)))
                .map(String::toUpperCase)
                .orElse(config.getDefaultProvider());
        log.info("Default AI provider cached: {}", cachedDefaultProviderId);
    }

    /** The settings page changed {@code default-ai-provider}: re-read it. */
    @EventListener
    public void onSettingChanged(AppSettingChangedEvent event) {
        if ("default-ai-provider".equals(event.key())) {
            refreshDefaultProviderCache();
        }
    }

    public void refreshDefaultProviderCache() {
        cachedDefaultProviderId = appSettingsRepository.get("default-ai-provider")
                .filter(v -> !v.isBlank() && providers.containsKey(v.toUpperCase(Locale.ROOT)))
                .map(String::toUpperCase)
                .orElse(config.getDefaultProvider());
        log.info("Default AI provider cache refreshed: {}", cachedDefaultProviderId);
    }

    /**
     * Resolves the provider for a given workflow setting.
     * Priority: workflowProvider > DB default > config default > CLAUDE.
     */
    public AiProvider resolve(String workflowProvider) {
        String id = resolveId(workflowProvider);
        AiProvider provider = providers.get(id);
        if (provider == null) {
            log.warn("Unknown AI provider '{}', falling back to default", id);
            provider = providers.get(getDefaultProviderId());
        }
        return provider;
    }

    /** Returns the global default provider. */
    public AiProvider getDefault() {
        return resolve(null);
    }

    /** Returns all registered provider IDs. */
    public List<String> getAvailableProviderIds() {
        return List.copyOf(providers.keySet());
    }

    /** Returns the global default provider ID (from cache — never reads DB during a request). */
    public String getDefaultProviderId() {
        return cachedDefaultProviderId != null ? cachedDefaultProviderId : config.getDefaultProvider();
    }

    /** Returns each registered provider's declared capabilities, keyed by provider ID. */
    public Map<String, AiProviderCapabilities> getCapabilitiesByProvider() {
        Map<String, AiProviderCapabilities> result = new java.util.LinkedHashMap<>();
        providers.forEach((id, provider) -> result.put(id, provider.getCapabilities()));
        return result;
    }

    private String resolveId(String workflowProvider) {
        if (workflowProvider != null && !workflowProvider.isBlank()) {
            return workflowProvider.toUpperCase(Locale.ROOT);
        }
        return getDefaultProviderId();
    }
}
