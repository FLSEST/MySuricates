package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.OAuthStateRegistry.PendingAuthorization;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;

@Service
@Slf4j
public class IntegrationOAuthService {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionService encryptionService;
    private final IntegrationRegistry registry;
    private final OAuthTokenClient tokenClient;
    private final OAuthStateRegistry stateRegistry;
    private final TransactionTemplate transactionTemplate;

    public IntegrationOAuthService(IntegrationConnectionRepository connectionRepository,
                                   IntegrationEncryptionService encryptionService,
                                   IntegrationRegistry registry,
                                   OAuthTokenClient tokenClient,
                                   OAuthStateRegistry stateRegistry,
                                   PlatformTransactionManager transactionManager) {
        this.connectionRepository = connectionRepository;
        this.encryptionService = encryptionService;
        this.registry = registry;
        this.tokenClient = tokenClient;
        this.stateRegistry = stateRegistry;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    /** Save OAuth client credentials (encrypted) for a provider; PAT connections are validated and their scopes recorded. */
    @Transactional
    public IntegrationConnection saveCredentials(String providerId, String clientId, String clientSecret) {
        IntegrationProvider provider = registry.getProvider(providerId);

        IntegrationConnection connection = connectionRepository.findByProviderId(providerId)
                .orElse(IntegrationConnection.builder().providerId(providerId).build());

        connection.setClientIdEncrypted(encryptionService.encrypt(clientId));
        connection.setClientSecretEncrypted(encryptionService.encrypt(clientSecret));

        // Non-OAuth providers: credentials ARE the auth — validate before connecting
        if (!provider.requiresOAuthFlow()) {
            String combinedToken = clientId + "|" + clientSecret;
            connection.setAccessTokenEncrypted(encryptionService.encrypt(combinedToken));
            String accountInfo = null;
            try {
                accountInfo = provider.fetchAccountInfo(combinedToken);
            } catch (Exception e) {
                log.warn("Credential validation failed for provider {}: {}", providerId, e.getMessage());
            }
            if (accountInfo != null) {
                connection.setAccountInfo(accountInfo);
                connection.setStatus(ConnectionStatus.CONNECTED);
                connection.setScopes(provider.fetchGrantedScopes(combinedToken)); // null = unknown
            } else {
                log.warn("Credential validation returned no account info for provider {}", providerId);
                connection.setStatus(ConnectionStatus.DISCONNECTED);
            }
        }

        log.info("Saved client credentials for provider: {}", providerId);
        return connectionRepository.save(connection);
    }

    /** Check if a provider has credentials configured (DB or env var). */
    public boolean isProviderConfigured(String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        // Check DB first
        Optional<IntegrationConnection> conn = connectionRepository.findByProviderId(providerId);
        if (conn.isPresent() && conn.get().getClientIdEncrypted() != null && conn.get().getClientSecretEncrypted() != null) {
            return true;
        }

        // Fallback: env vars
        return System.getenv(config.clientIdEnvVar()) != null
                && System.getenv(config.clientSecretEnvVar()) != null;
    }

    /** Generate an OAuth2 authorization URL for the given provider. */
    public String getAuthorizationUrl(String providerId, String redirectUri, java.util.List<String> additionalScopes) {
        return getAuthorizationUrl(providerId, redirectUri, additionalScopes, null);
    }

    public String getAuthorizationUrl(String providerId, String redirectUri,
                                      java.util.List<String> additionalScopes, String callbackOrigin) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        String clientId = resolveClientId(providerId, config);
        if (clientId == null) {
            throw new IllegalStateException("No client credentials configured for provider: " + providerId);
        }

        String state = stateRegistry.register(providerId, callbackOrigin);

        return OAuthAuthorizationUrl.build(config, clientId, redirectUri, additionalScopes, state);
    }

    /** Handle the OAuth2 callback: exchange code for tokens and store them. */
    @Transactional
    public IntegrationConnection handleCallback(String code, String state, String redirectUri) {
        PendingAuthorization pending = stateRegistry.consume(state)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OAuth state"));
        String providerId = pending.providerId();

        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        String clientId = resolveClientId(providerId, config);
        String clientSecret = resolveClientSecret(providerId, config);

        JsonNode tokenResponse = tokenClient.exchangeCodeForTokens(
                providerId, config, code, clientId, clientSecret, redirectUri);

        String accessToken = tokenResponse.get("access_token").asText();
        String refreshToken = tokenResponse.has("refresh_token") ? tokenResponse.get("refresh_token").asText() : null;
        int expiresIn = tokenResponse.has("expires_in") ? tokenResponse.get("expires_in").asInt() : 3600;
        String grantedScopes = tokenResponse.has("scope") ? tokenResponse.get("scope").asText() : String.join(" ", config.defaultScopes());

        String accountInfo = fetchAccountInfo(providerId, accessToken);

        IntegrationConnection connection = connectionRepository.findByProviderId(providerId)
                .orElse(IntegrationConnection.builder().providerId(providerId).build());

        connection.setStatus(ConnectionStatus.CONNECTED);
        connection.setAccessTokenEncrypted(encryptionService.encrypt(accessToken));
        connection.setRefreshTokenEncrypted(refreshToken != null ? encryptionService.encrypt(refreshToken) : connection.getRefreshTokenEncrypted());
        connection.setTokenExpiry(Instant.now().plusSeconds(expiresIn));
        connection.setScopes(grantedScopes);
        connection.setAccountInfo(accountInfo);

        return connectionRepository.save(connection);
    }

    /** Read before consuming the state in handleCallback; never supplied by the callback request. */
    public String getCallbackOrigin(String state) {
        return stateRegistry.peek(state)
                .orElseThrow(() -> new IllegalArgumentException("Invalid or expired OAuth state"))
                .callbackOrigin();
    }

    /** Get a valid access token, refreshing if necessary; null for a credential-less provider with none stored. */
    public String getValidAccessToken(String providerId) {
        IntegrationProvider provider = registry.getProvider(providerId);
        IntegrationConnection connection = connectionRepository.findByProviderId(providerId).orElse(null);
        if (connection == null) {
            if (provider.requiresCredentials()) throw new IllegalStateException("No connection for provider: " + providerId);
            log.debug("Provider {} requires no credentials — proceeding without token", providerId);
            return null;
        }

        if (connection.getStatus() == ConnectionStatus.DISCONNECTED) {
            throw new IllegalStateException("Provider " + providerId + " is not connected");
        }

        // Non-OAuth providers use stored credentials directly (no expiry/refresh)
        if (!provider.requiresOAuthFlow()) {
            try {
                return encryptionService.decrypt(connection.getAccessTokenEncrypted());
            } catch (IllegalStateException e) {
                if (provider.requiresCredentials()) throw e;
                log.debug("Provider {} requires no credentials — proceeding without token", providerId);
                return null;
            }
        }

        if (connection.getTokenExpiry() != null && connection.getTokenExpiry().isAfter(Instant.now().plusSeconds(300))) {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        }

        return refreshAccessToken(connection);
    }

    /**
     * Disconnect a provider: clear all token-related fields, then revoke the token at the provider.
     * App credentials (clientId / clientSecret) are kept so a reconnect doesn't require
     * re-entering them. Use {@link #deleteCredentials} for full removal of the row.
     *
     * <p>Deliberately <b>not</b> {@code @Transactional}: the revocation is an HTTP round-trip with
     * a 10 s timeout, and with HikariCP pool-size 1 an enclosing transaction would pin the single
     * connection for that whole time. Short {@link TransactionTemplate} write burst first, revoke
     * afterwards — so a failing or hanging provider cannot leave the row "revoked but CONNECTED".
     */
    public void disconnect(String providerId) {
        String token = transactionTemplate.execute(status ->
                connectionRepository.findByProviderId(providerId).map(connection -> {
                    String revocable = revocableToken(providerId, connection);
                    clearTokens(connection);
                    connectionRepository.save(connection);
                    return revocable;
                }).orElse(null));
        revoke(providerId, token);
        log.info("Disconnected integration (kept credentials): {}", providerId);
    }

    /**
     * Delete the connection row entirely (app credentials included); reconnect requires re-entering them.
     *
     * <p>Same burst-then-revoke shape as {@link #disconnect} — deliberately not {@code @Transactional}.
     */
    public void deleteCredentials(String providerId) {
        String token = transactionTemplate.execute(status ->
                connectionRepository.findByProviderId(providerId).map(connection -> {
                    String revocable = revocableToken(providerId, connection);
                    connectionRepository.delete(connection);
                    return revocable;
                }).orElse(null));
        revoke(providerId, token);
        log.info("Deleted integration credentials: {}", providerId);
    }

    /** Get the current connection status for a provider. */
    public Optional<IntegrationConnection> getConnection(String providerId) {
        return connectionRepository.findByProviderId(providerId);
    }

    /**
     * The decrypted access token if it can be revoked server-side, else {@code null}
     * (non-OAuth tokens like Trello API keys cannot, and neither can an undecryptable one).
     */
    private String revocableToken(String providerId, IntegrationConnection connection) {
        IntegrationProvider provider = registry.getProvider(providerId);
        if (!provider.requiresOAuthFlow() || connection.getAccessTokenEncrypted() == null) {
            return null;
        }
        try {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        } catch (Exception e) {
            log.warn("Cannot decrypt access token of {} for revocation: {}", providerId, e.getMessage());
            return null;
        }
    }

    /** Revoke at the provider — outside any transaction, and never fatal. */
    private void revoke(String providerId, String token) {
        if (token == null) return;
        tokenClient.revoke(providerId, registry.getProvider(providerId).getOAuthConfig(), token);
    }

    private static void clearTokens(IntegrationConnection connection) {
        connection.setAccessTokenEncrypted(null);
        connection.setRefreshTokenEncrypted(null);
        connection.setTokenExpiry(null);
        connection.setScopes(null);
        connection.setAccountInfo(null);
        connection.setStatus(ConnectionStatus.DISCONNECTED);
    }

    private String refreshAccessToken(IntegrationConnection connection) {
        String providerId = connection.getProviderId();
        String refreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null) {
            markExpired(connection);
            throw new IllegalStateException("No refresh token available for provider: " + providerId);
        }

        OAuthConfig config = registry.getProvider(providerId).getOAuthConfig();
        String clientId = resolveClientId(providerId, config);
        String clientSecret = resolveClientSecret(providerId, config);

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
            throw new IllegalStateException("Token refresh failed for " + providerId + " (no access_token in response)");
        }

        int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;

        connection.setAccessTokenEncrypted(encryptionService.encrypt(newAccessToken));
        connection.setTokenExpiry(Instant.now().plusSeconds(expiresIn));
        connection.setStatus(ConnectionStatus.CONNECTED);

        if (json.has("refresh_token")) {
            connection.setRefreshTokenEncrypted(encryptionService.encrypt(json.get("refresh_token").asText()));
        }

        connectionRepository.save(connection);
        log.debug("Refreshed access token for {}", providerId);
        return newAccessToken;
    }

    private void markExpired(IntegrationConnection connection) {
        connection.setStatus(ConnectionStatus.EXPIRED);
        connectionRepository.save(connection);
    }

    private String fetchAccountInfo(String providerId, String accessToken) {
        try {
            IntegrationProvider provider = registry.getProvider(providerId);
            return provider.fetchAccountInfo(accessToken);
        } catch (Exception e) {
            log.warn("Failed to fetch account info for {}: {}", providerId, e.getMessage());
        }
        return null;
    }

    /** Resolve client ID: DB first, then env var fallback */
    private String resolveClientId(String providerId, OAuthConfig config) {
        return connectionRepository.findByProviderId(providerId)
                .filter(c -> c.getClientIdEncrypted() != null)
                .map(c -> encryptionService.decrypt(c.getClientIdEncrypted()))
                .orElseGet(() -> System.getenv(config.clientIdEnvVar()));
    }

    /** Resolve client secret: DB first, then env var fallback */
    private String resolveClientSecret(String providerId, OAuthConfig config) {
        return connectionRepository.findByProviderId(providerId)
                .filter(c -> c.getClientSecretEncrypted() != null)
                .map(c -> encryptionService.decrypt(c.getClientSecretEncrypted()))
                .orElseGet(() -> System.getenv(config.clientSecretEnvVar()));
    }
}
