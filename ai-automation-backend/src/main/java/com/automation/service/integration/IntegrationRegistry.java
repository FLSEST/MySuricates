package com.automation.service.integration;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
public class IntegrationRegistry {

    private final Map<String, IntegrationProvider> providers = new ConcurrentHashMap<>();

    public IntegrationRegistry(List<IntegrationProvider> providerList) {
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
        return provider;
    }

    public Collection<IntegrationProvider> getAllProviders() {
        return providers.values();
    }

    public boolean hasProvider(String providerId) {
        return providers.containsKey(providerId);
    }
}
