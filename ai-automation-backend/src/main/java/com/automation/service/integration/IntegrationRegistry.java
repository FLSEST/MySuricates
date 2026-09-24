package com.automation.service.integration;

import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * All integration providers. A provider whose {@link IntegrationProvider#requiredModule()} is off
 * counts as absent: it is not listed, and looking it up fails with the module's disabled message.
 * Checked per call — no startup snapshot — so a runtime module switch takes effect at once.
 */
@Service
@Slf4j
public class IntegrationRegistry {

    private final Map<String, IntegrationProvider> providers = new ConcurrentHashMap<>();
    private final ModuleRegistry modules;

    /** Without module information every provider is available (tests, tools). */
    public IntegrationRegistry(List<IntegrationProvider> providerList) {
        this(providerList, ModuleRegistry.allEnabled());
    }

    @Autowired
    public IntegrationRegistry(List<IntegrationProvider> providerList, ModuleRegistry modules) {
        this.modules = modules;
        for (IntegrationProvider provider : providerList) {
            providers.put(provider.getProviderId(), provider);
            log.info("Registered integration provider: {}", provider.getProviderId());
        }
    }

    public IntegrationProvider getProvider(String providerId) {
        IntegrationProvider provider = providers.get(providerId);
        if (provider == null) {
            throw new IllegalArgumentException("Unknown integration provider: " + providerId);
        }
        ModuleCatalog required = provider.requiredModule();
        if (required != null && !modules.isEnabled(required)) {
            throw new IllegalArgumentException(required.disabledMessage("Integration provider '" + providerId + "'"));
        }
        return provider;
    }

    public Collection<IntegrationProvider> getAllProviders() {
        return providers.values().stream().filter(this::available).toList();
    }

    /** All available providers that can receive a TODO as an external task (GitHub, Jira, …). */
    public List<IntegrationProvider> getTaskExportProviders() {
        return providers.values().stream()
                .filter(this::available)
                .filter(p -> p instanceof TaskExportCapable)
                .toList();
    }

    public boolean hasProvider(String providerId) {
        IntegrationProvider provider = providers.get(providerId);
        return provider != null && available(provider);
    }

    private boolean available(IntegrationProvider provider) {
        ModuleCatalog required = provider.requiredModule();
        return required == null || modules.isEnabled(required);
    }
}
