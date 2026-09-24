package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

/**
 * Refreshes the OAuth access token of one connection. The refresh is provider HTTP; the
 * repository saves run in their own short transactions — deliberately no {@code @Transactional}.
 * Extracted from {@link IntegrationOAuthService} to keep that class under its ratchet ceiling.
 */
@Slf4j
class IntegrationTokenRefresher {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionService encryptionService;
    private final IntegrationRegistry registry;
    private final OAuthTokenClient tokenClient;
    private final IntegrationOAuthConnectFlow connectFlow;

    IntegrationTokenRefresher(IntegrationConnectionRepository connectionRepository,
                              IntegrationEncryptionService encryptionService,
                              IntegrationRegistry registry,
                              OAuthTokenClient tokenClient,
                              IntegrationOAuthConnectFlow connectFlow) {
        this.connectionRepository = connectionRepository;
        this.encryptionService = encryptionService;
        this.registry = registry;
        this.tokenClient = tokenClient;
        this.connectFlow = connectFlow;
    }

    String refresh(IntegrationConnection connection) {
        String providerId = connection.getProviderId();
        String connectionName = connection.getConnectionName();
        String label = IntegrationConnectionNames.label(providerId, connectionName);
        String refreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null) {
            markExpired(connection);
            throw new IllegalStateException("No refresh token available for provider: " + label);
        }

        OAuthConfig config = registry.getProvider(providerId).getOAuthConfig();
        String clientId = connectFlow.resolveClientId(providerId, connectionName, config);
        String clientSecret = connectFlow.resolveClientSecret(providerId, connectionName, config);

        JsonNode json;
        try {
            json = tokenClient.refreshAccessToken(providerId, config, refreshToken, clientId, clientSecret);
        } catch (IllegalStateException e) {
            markExpired(connection);
            throw e;
        }

        String newAccessToken = json.path("access_token").asText(null);
        if (newAccessToken == null) {
            markExpired(connection);
            throw new IllegalStateException("Token refresh failed for " + label + " (no access_token in response)");
        }

        int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;

        connection.setAccessTokenEncrypted(encryptionService.encrypt(newAccessToken));
        connection.setTokenExpiry(Instant.now().plusSeconds(expiresIn));
        connection.setStatus(ConnectionStatus.CONNECTED);

        if (json.has("refresh_token")) {
            connection.setRefreshTokenEncrypted(encryptionService.encrypt(json.get("refresh_token").asText()));
        }

        connectionRepository.save(connection);
        log.debug("Refreshed access token for {}", label);
        return newAccessToken;
    }

    private void markExpired(IntegrationConnection connection) {
        connection.setStatus(ConnectionStatus.EXPIRED);
        connectionRepository.save(connection);
    }
}
