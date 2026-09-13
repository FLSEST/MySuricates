package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.function.Consumer;

/**
 * The two "connect" paths of an integration: saving credentials (PAT validation) and the
 * OAuth callback (code-for-token exchange). Both talk to the provider over HTTP with a
 * 10 s timeout; with HikariCP pool-size 1 that must never happen inside a transaction, or
 * the single connection is pinned for the whole round-trip (Audit-TODO 56e07e83).
 *
 * <p>Shape of every entry point, mirroring {@link IntegrationOAuthService#disconnect}:
 * short read burst ({@link TransactionTemplate}) → provider HTTP outside any transaction →
 * short write burst. Deliberately no {@code @Transactional} anywhere in this class.
 * Extracted from {@link IntegrationOAuthService} to keep that class under its ratchet ceiling.
 */
@Slf4j
class IntegrationOAuthConnectFlow {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionService encryptionService;
    private final IntegrationRegistry registry;
    private final OAuthTokenClient tokenClient;
    private final TransactionTemplate transactionTemplate;

    IntegrationOAuthConnectFlow(IntegrationConnectionRepository connectionRepository,
                                IntegrationEncryptionService encryptionService,
                                IntegrationRegistry registry,
                                OAuthTokenClient tokenClient,
                                TransactionTemplate transactionTemplate) {
        this.connectionRepository = connectionRepository;
        this.encryptionService = encryptionService;
        this.registry = registry;
        this.tokenClient = tokenClient;
        this.transactionTemplate = transactionTemplate;
    }

    /** Client id / secret as resolved for a provider (DB first, env var fallback). */
    record ClientCredentials(String clientId, String clientSecret) {}

    /** Save OAuth client credentials (encrypted); PAT connections are validated at the provider first. */
    IntegrationConnection saveCredentials(String providerId, String clientId, String clientSecret) {
        IntegrationProvider provider = registry.getProvider(providerId);
        String clientIdEncrypted = encryptionService.encrypt(clientId);
        String clientSecretEncrypted = encryptionService.encrypt(clientSecret);

        if (provider.requiresOAuthFlow()) {
            IntegrationConnection saved = persist(providerId, connection -> {
                connection.setClientIdEncrypted(clientIdEncrypted);
                connection.setClientSecretEncrypted(clientSecretEncrypted);
            });
            log.info("Saved client credentials for provider: {}", providerId);
            return saved;
        }

        // Non-OAuth providers: credentials ARE the auth — validate at the provider (HTTP) before
        // touching the DB, so the write burst afterwards is a plain save.
        String combinedToken = clientId + "|" + clientSecret;
        String accessTokenEncrypted = encryptionService.encrypt(combinedToken);
        String accountInfo = validateCredentials(provider, providerId, combinedToken);
        String scopes = accountInfo != null ? provider.fetchGrantedScopes(combinedToken) : null; // null = unknown

        IntegrationConnection saved = persist(providerId, connection -> {
            connection.setClientIdEncrypted(clientIdEncrypted);
            connection.setClientSecretEncrypted(clientSecretEncrypted);
            connection.setAccessTokenEncrypted(accessTokenEncrypted);
            if (accountInfo != null) {
                connection.setAccountInfo(accountInfo);
                connection.setStatus(ConnectionStatus.CONNECTED);
                connection.setScopes(scopes);
            } else {
                connection.setStatus(ConnectionStatus.DISCONNECTED);
            }
        });
        log.info("Saved client credentials for provider: {}", providerId);
        return saved;
    }

    /** OAuth callback for an already-consumed state: exchange the code, then store the tokens. */
    IntegrationConnection handleCallback(String providerId, String code, String redirectUri) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        // Burst 1: client credentials (DB read + decrypt)
        ClientCredentials credentials = transactionTemplate.execute(status -> resolveClientCredentials(providerId, config));

        // Provider HTTP — outside any transaction
        JsonNode tokenResponse = tokenClient.exchangeCodeForTokens(
                providerId, config, code, credentials.clientId(), credentials.clientSecret(), redirectUri);

        String accessToken = tokenResponse.path("access_token").asText(null);
        if (accessToken == null) {
            throw new IllegalStateException("Token exchange failed for " + providerId + " (no access_token in response)");
        }
        String refreshToken = tokenResponse.has("refresh_token") ? tokenResponse.get("refresh_token").asText() : null;
        int expiresIn = tokenResponse.has("expires_in") ? tokenResponse.get("expires_in").asInt() : 3600;
        String grantedScopes = tokenResponse.has("scope") ? tokenResponse.get("scope").asText() : String.join(" ", config.defaultScopes());

        String accountInfo = fetchAccountInfo(provider, providerId, accessToken);

        String accessTokenEncrypted = encryptionService.encrypt(accessToken);
        String refreshTokenEncrypted = refreshToken != null ? encryptionService.encrypt(refreshToken) : null;
        Instant tokenExpiry = Instant.now().plusSeconds(expiresIn);

        // Burst 2: persist the connected row
        return persist(providerId, connection -> {
            connection.setStatus(ConnectionStatus.CONNECTED);
            connection.setAccessTokenEncrypted(accessTokenEncrypted);
            if (refreshTokenEncrypted != null) {
                connection.setRefreshTokenEncrypted(refreshTokenEncrypted);
            }
            connection.setTokenExpiry(tokenExpiry);
            connection.setScopes(grantedScopes);
            connection.setAccountInfo(accountInfo);
        });
    }

    ClientCredentials resolveClientCredentials(String providerId, OAuthConfig config) {
        return new ClientCredentials(resolveClientId(providerId, config), resolveClientSecret(providerId, config));
    }

    /** Resolve client ID: DB first, then env var fallback */
    String resolveClientId(String providerId, OAuthConfig config) {
        return connectionRepository.findByProviderId(providerId)
                .filter(c -> c.getClientIdEncrypted() != null)
                .map(c -> encryptionService.decrypt(c.getClientIdEncrypted()))
                .orElseGet(() -> System.getenv(config.clientIdEnvVar()));
    }

    /** Resolve client secret: DB first, then env var fallback */
    String resolveClientSecret(String providerId, OAuthConfig config) {
        return connectionRepository.findByProviderId(providerId)
                .filter(c -> c.getClientSecretEncrypted() != null)
                .map(c -> encryptionService.decrypt(c.getClientSecretEncrypted()))
                .orElseGet(() -> System.getenv(config.clientSecretEnvVar()));
    }

    /** Load-or-create the provider's row, apply the mutation and save — one short write burst. */
    private IntegrationConnection persist(String providerId, Consumer<IntegrationConnection> mutation) {
        return transactionTemplate.execute(status -> {
            IntegrationConnection connection = connectionRepository.findByProviderId(providerId)
                    .orElse(IntegrationConnection.builder().providerId(providerId).build());
            mutation.accept(connection);
            return connectionRepository.save(connection);
        });
    }

    /** Account info for a PAT-style credential, or {@code null} when the provider rejects it. */
    private static String validateCredentials(IntegrationProvider provider, String providerId, String token) {
        String accountInfo = null;
        try {
            accountInfo = provider.fetchAccountInfo(token);
        } catch (Exception e) {
            log.warn("Credential validation failed for provider {}: {}", providerId, e.getMessage());
        }
        if (accountInfo == null) {
            log.warn("Credential validation returned no account info for provider {}", providerId);
        }
        return accountInfo;
    }

    private static String fetchAccountInfo(IntegrationProvider provider, String providerId, String accessToken) {
        try {
            return provider.fetchAccountInfo(accessToken);
        } catch (Exception e) {
            log.warn("Failed to fetch account info for {}: {}", providerId, e.getMessage());
        }
        return null;
    }
}
