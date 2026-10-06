package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.repository.IntegrationConnectionRepository;

import java.util.List;

/**
 * Whether a connection has credentials, for {@link IntegrationOAuthService}. Extracted from it to
 * keep that class under its ratchet ceiling; callers pass a normalized connection name.
 */
final class IntegrationCredentialCheck {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationRegistry registry;

    IntegrationCredentialCheck(IntegrationConnectionRepository connectionRepository, IntegrationRegistry registry) {
        this.connectionRepository = connectionRepository;
        this.registry = registry;
    }

    /**
     * Whether a connection has credentials: its own row, for an OAuth provider also the app
     * credentials of the {@code default} row or the env vars (a second account signs in through
     * the same OAuth app).
     */
    boolean isConfigured(String providerId, String connectionName) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();
        if (hasStoredCredentials(providerId, connectionName)) return true;
        if (!IntegrationConnectionNames.isDefault(connectionName)) {
            if (!provider.requiresOAuthFlow()) return false;
            if (hasStoredCredentials(providerId, IntegrationConnectionNames.DEFAULT)) return true;
        }
        return hasEnvCredentials(config);
    }

    /**
     * {@link #isConfigured} for the {@code default} connection, answered from the provider's
     * connections the caller has already loaded — no query, so a provider list costs one query in
     * total instead of two per provider (TODO 942722f5).
     */
    boolean isDefaultConfigured(String providerId, List<IntegrationConnection> connections) {
        boolean stored = connections.stream()
                .filter(c -> IntegrationConnectionNames.isDefault(c.getConnectionName()))
                .anyMatch(IntegrationCredentialCheck::hasClientCredentials);
        return stored || hasEnvCredentials(registry.getProvider(providerId).getOAuthConfig());
    }

    private boolean hasStoredCredentials(String providerId, String connectionName) {
        return connectionRepository.findByProviderIdAndConnectionName(providerId, connectionName)
                .filter(IntegrationCredentialCheck::hasClientCredentials)
                .isPresent();
    }

    private static boolean hasClientCredentials(IntegrationConnection connection) {
        return connection.getClientIdEncrypted() != null && connection.getClientSecretEncrypted() != null;
    }

    private static boolean hasEnvCredentials(OAuthConfig config) {
        return System.getenv(config.clientIdEnvVar()) != null
                && System.getenv(config.clientSecretEnvVar()) != null;
    }
}
