package com.automation.service.ai;

import com.automation.config.AiProviderConfig;
import com.automation.repository.AppSettingsRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
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

    public AiProviderRegistry(List<AiProvider> providerList, AiProviderConfig config,
                               AppSettingsRepository appSettingsRepository) {
        this.providers = providerList.stream()
                .collect(Collectors.toMap(AiProvider::getId, Function.identity()));
        this.config = config;
        this.appSettingsRepository = appSettingsRepository;
        log.info("AI providers registered: {} (default: {})",
                providers.keySet(), config.getDefaultProvider());
    }

    /**
     * Resolves the provider for a given workflow setting.
     * Priority: workflowProvider > DB default > config default > CLAUDE.
     */
    public AiProvider resolve(String workflowProvider) {
        String id = resolveId(workflowProvider);
        AiProvider provider = providers.get(id);
        if (provider == null) {
            log.warn("Unknown AI provider '{}', falling back to CLAUDE", id);
            provider = providers.get("CLAUDE");
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

    /** Returns the global default provider ID (DB setting > config fallback). */
    public String getDefaultProviderId() {
        return appSettingsRepository.get("default-ai-provider")
                .filter(v -> !v.isBlank() && providers.containsKey(v.toUpperCase()))
                .map(String::toUpperCase)
                .orElse(config.getDefaultProvider());
    }

    private String resolveId(String workflowProvider) {
        if (workflowProvider != null && !workflowProvider.isBlank()) {
            return workflowProvider.toUpperCase();
        }
        return getDefaultProviderId();
    }
}
