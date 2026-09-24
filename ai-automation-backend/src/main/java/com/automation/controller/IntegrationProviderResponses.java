package com.automation.controller;

import com.automation.dto.response.IntegrationActionResponse;
import com.automation.dto.response.IntegrationConnectionResponse;
import com.automation.dto.response.IntegrationProviderResponse;
import com.automation.entity.IntegrationConnection;
import com.automation.service.integration.IntegrationConnectionNames;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.TaskExportCapable;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * Builds the integration responses of {@link IntegrationController}: the provider card (whose
 * top-level status describes the {@code default} connection) and one entry per named connection.
 * Extracted from the controller when named connections came in (0b3cbcc3).
 */
@Slf4j
final class IntegrationProviderResponses {

    private final ObjectMapper objectMapper;

    IntegrationProviderResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * @param connections every stored connection of the provider, {@code default} first
     * @param configured  whether the {@code default} connection has credentials (DB or env)
     */
    IntegrationProviderResponse provider(IntegrationProvider provider, List<IntegrationConnection> connections,
                                         boolean configured) {
        IntegrationConnection connection = connections.stream()
                .filter(c -> IntegrationConnectionNames.isDefault(c.getConnectionName()))
                .findFirst().orElse(null);
        String status = connection != null ? connection.getStatus().name() : "DISCONNECTED";
        Account account = account(provider, connection);
        String scopes = connection != null ? connection.getScopes() : null;
        Set<String> grantedScopes = parseScopes(scopes);

        List<IntegrationProviderResponse.ScopeOptionResponse> optionalScopes = provider.getOAuthConfig()
                .optionalScopes().stream()
                .map(s -> new IntegrationProviderResponse.ScopeOptionResponse(s, s, grantedScopes.contains(s)))
                .toList();

        return new IntegrationProviderResponse(
                provider.getProviderId(),
                provider.getDisplayName(),
                provider.getIcon(),
                provider.getDescription(),
                status,
                configured,
                provider.requiresCredentials(),
                account.email(),
                account.name(),
                connection != null ? connection.getCreatedAt() : null,
                scopes,
                optionalScopes,
                actions(provider, scopes),
                provider instanceof TaskExportCapable,
                provider.getVerification().name(),
                provider.getVerifiedAgainst(),
                connections.stream().map(c -> connection(provider, c, configured)).toList(),
                provider.requiresOAuthFlow()
        );
    }

    /**
     * A connection counts as configured when its own row holds app credentials — or, for an OAuth
     * provider, when the {@code default} connection is configured: a second account signs in
     * through the same OAuth app.
     */
    IntegrationConnectionResponse connection(IntegrationProvider provider, IntegrationConnection connection,
                                             boolean defaultConfigured) {
        boolean own = connection.getClientIdEncrypted() != null && connection.getClientSecretEncrypted() != null;
        boolean configured = own || (provider.requiresOAuthFlow() && defaultConfigured)
                || (IntegrationConnectionNames.isDefault(connection.getConnectionName()) && defaultConfigured);
        Account account = account(provider, connection);
        return new IntegrationConnectionResponse(
                connection.getConnectionName(),
                connection.getStatus().name(),
                configured,
                account.email(),
                account.name(),
                connection.getCreatedAt(),
                connection.getScopes());
    }

    List<IntegrationActionResponse> actions(IntegrationProvider provider, String scopes) {
        return provider.getAvailableActions().stream()
                .map(a -> new IntegrationActionResponse(a.name(), a.description(), a.inputSchema(),
                        IntegrationController.isActionAvailable(a, scopes)))
                .toList();
    }

    static Set<String> parseScopes(String scopes) {
        if (scopes == null || scopes.isBlank()) return Set.of();
        return Set.copyOf(List.of(scopes.trim().split("\\s+")));
    }

    private record Account(String email, String name) {}

    private Account account(IntegrationProvider provider, IntegrationConnection connection) {
        if (connection == null || connection.getAccountInfo() == null) return new Account(null, null);
        try {
            JsonNode info = objectMapper.readTree(connection.getAccountInfo());
            return new Account(info.has("email") ? info.get("email").asText() : null,
                    info.has("name") ? info.get("name").asText() : null);
        } catch (Exception e) {
            log.warn("Failed to parse account info for {}", provider.getProviderId());
            return new Account(null, null);
        }
    }
}
