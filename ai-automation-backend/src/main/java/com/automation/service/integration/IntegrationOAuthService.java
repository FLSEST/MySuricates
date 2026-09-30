package com.automation.service.integration;

import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.entity.IntegrationConnection;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.OAuthStateRegistry.PendingAuthorization;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Credentials, OAuth flow and tokens of the integration connections. A provider may have several
 * named connections ({@link IntegrationConnectionNames}); every method without a connection name
 * works on the {@code default} one, so pre-V86 callers keep their behaviour.
 */
@Service
@Slf4j
public class IntegrationOAuthService {

    private static final String DEFAULT = IntegrationConnectionNames.DEFAULT;

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionService encryptionService;
    private final IntegrationRegistry registry;
    private final OAuthTokenClient tokenClient;
    private final OAuthStateRegistry stateRegistry;
    private final TransactionTemplate transactionTemplate;
    private final IntegrationOAuthConnectFlow connectFlow;
    private final IntegrationTokenRefresher tokenRefresher;

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
        this.connectFlow = new IntegrationOAuthConnectFlow(
                connectionRepository, encryptionService, registry, tokenClient, this.transactionTemplate);
        this.tokenRefresher = new IntegrationTokenRefresher(
                connectionRepository, encryptionService, registry, tokenClient, connectFlow);
    }

    /**
     * Save OAuth client credentials (encrypted) for a provider; PAT connections are validated and
     * their scopes recorded. Provider HTTP runs outside any transaction — see
     * {@link IntegrationOAuthConnectFlow}; deliberately not {@code @Transactional}.
     */
    public IntegrationConnection saveCredentials(String providerId, String clientId, String clientSecret) {
        return saveCredentials(providerId, DEFAULT, clientId, clientSecret);
    }

    /** {@link #saveCredentials(String, String, String)} for a named connection (created if new). */
    public IntegrationConnection saveCredentials(String providerId, String connectionName, String clientId, String clientSecret) {
        return connectFlow.saveCredentials(providerId, name(connectionName), clientId, clientSecret);
    }

    /** Check if a provider has credentials configured (DB or env var). */
    public boolean isProviderConfigured(String providerId) {
        return isProviderConfigured(providerId, DEFAULT);
    }

    /**
     * Whether a connection has credentials: its own row, for an OAuth provider also the app
     * credentials of the {@code default} row or the env vars (a second account signs in through
     * the same OAuth app).
     */
    public boolean isProviderConfigured(String providerId, String connectionName) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();
        String name = name(connectionName);
        if (hasStoredCredentials(providerId, name)) return true;
        if (!IntegrationConnectionNames.isDefault(name)) {
            if (!provider.requiresOAuthFlow()) return false;
            if (hasStoredCredentials(providerId, DEFAULT)) return true;
        }

        return System.getenv(config.clientIdEnvVar()) != null
                && System.getenv(config.clientSecretEnvVar()) != null;
    }

    private boolean hasStoredCredentials(String providerId, String connectionName) {
        return connectionRepository.findByProviderIdAndConnectionName(providerId, connectionName)
                .filter(c -> c.getClientIdEncrypted() != null && c.getClientSecretEncrypted() != null)
                .isPresent();
    }

    /** Generate an OAuth2 authorization URL for the given provider. */
    public String getAuthorizationUrl(String providerId, String redirectUri, List<String> additionalScopes) {
        return getAuthorizationUrl(providerId, redirectUri, additionalScopes, null);
    }

    public String getAuthorizationUrl(String providerId, String redirectUri,
                                      List<String> additionalScopes, String callbackOrigin) {
        return getAuthorizationUrl(providerId, DEFAULT, redirectUri, additionalScopes, callbackOrigin);
    }

    /** Authorization URL for a named connection; the name travels in the OAuth {@code state}, not in the URL. */
    public String getAuthorizationUrl(String providerId, String connectionName, String redirectUri,
                                      List<String> additionalScopes, String callbackOrigin) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();
        String name = name(connectionName);

        String clientId = connectFlow.resolveClientId(providerId, name, config);
        if (clientId == null) {
            throw new IllegalStateException("No client credentials configured for provider: "
                    + IntegrationConnectionNames.label(providerId, name));
        }

        String state = stateRegistry.register(providerId, name, callbackOrigin);

        return OAuthAuthorizationUrl.build(config, clientId, redirectUri, additionalScopes, state);
    }

    /**
     * Handle the OAuth2 callback: exchange code for tokens and store them in the connection named
     * in the state. The exchange and the account-info lookup are provider HTTP and run outside any
     * transaction — see {@link IntegrationOAuthConnectFlow}; deliberately not {@code @Transactional}.
     */
    public IntegrationConnection handleCallback(String code, String state, String redirectUri) {
        PendingAuthorization pending = stateRegistry.consume(state)
                .orElseThrow(() -> new LocalizedArgumentException(ErrorMessage.OAUTH_STATE_INVALID));
        return connectFlow.handleCallback(pending.providerId(), pending.connectionName(), code, redirectUri);
    }

    /** Read before consuming the state in handleCallback; never supplied by the callback request. */
    public String getCallbackOrigin(String state) {
        return stateRegistry.peek(state)
                .orElseThrow(() -> new LocalizedArgumentException(ErrorMessage.OAUTH_STATE_INVALID))
                .callbackOrigin();
    }

    /** Get a valid access token, refreshing if necessary; null for a credential-less provider with none stored. */
    public String getValidAccessToken(String providerId) {
        return getValidAccessToken(providerId, DEFAULT);
    }

    /** {@link #getValidAccessToken(String)} for a named connection. */
    public String getValidAccessToken(String providerId, String connectionName) {
        IntegrationProvider provider = registry.getProvider(providerId);
        String name = name(connectionName);
        String label = IntegrationConnectionNames.label(providerId, name);
        IntegrationConnection connection = connectionRepository.findByProviderIdAndConnectionName(providerId, name).orElse(null);
        if (connection == null) {
            if (provider.requiresCredentials()) throw new IllegalStateException("No connection for provider: " + label);
            log.debug("Provider {} requires no credentials — proceeding without token", label);
            return null;
        }

        if (connection.getStatus() == ConnectionStatus.DISCONNECTED) {
            throw new IllegalStateException("Provider " + label + " is not connected");
        }

        // Non-OAuth providers use stored credentials directly (no expiry/refresh)
        if (!provider.requiresOAuthFlow()) {
            try {
                return encryptionService.decrypt(connection.getAccessTokenEncrypted());
            } catch (IllegalStateException e) {
                if (provider.requiresCredentials()) throw e;
                log.debug("Provider {} requires no credentials — proceeding without token", label);
                return null;
            }
        }

        if (connection.getTokenExpiry() != null && connection.getTokenExpiry().isAfter(Instant.now().plusSeconds(300))) {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        }

        return tokenRefresher.refresh(connection);
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
        disconnect(providerId, DEFAULT);
    }

    /** {@link #disconnect(String)} for a named connection; the other connections stay untouched. */
    public void disconnect(String providerId, String connectionName) {
        String name = name(connectionName);
        String token = transactionTemplate.execute(status ->
                connectionRepository.findByProviderIdAndConnectionName(providerId, name).map(connection -> {
                    String revocable = revocableToken(providerId, connection);
                    clearTokens(connection);
                    connectionRepository.save(connection);
                    return revocable;
                }).orElse(null));
        revoke(providerId, token);
        log.info("Disconnected integration (kept credentials): {}", IntegrationConnectionNames.label(providerId, name));
    }

    /** Delete the connection row entirely (app credentials included); reconnect requires re-entering them.
     * Same burst-then-revoke shape as {@link #disconnect} — deliberately not {@code @Transactional}. */
    public void deleteCredentials(String providerId) {
        deleteCredentials(providerId, DEFAULT);
    }

    /** {@link #deleteCredentials(String)} for a named connection; the other connections stay untouched. */
    public void deleteCredentials(String providerId, String connectionName) {
        String name = name(connectionName);
        String token = transactionTemplate.execute(status ->
                connectionRepository.findByProviderIdAndConnectionName(providerId, name).map(connection -> {
                    String revocable = revocableToken(providerId, connection);
                    connectionRepository.delete(connection);
                    return revocable;
                }).orElse(null));
        revoke(providerId, token);
        log.info("Deleted integration credentials: {}", IntegrationConnectionNames.label(providerId, name));
    }

    /** Get the current connection status for a provider. */
    public Optional<IntegrationConnection> getConnection(String providerId) {
        return getConnection(providerId, DEFAULT);
    }

    /** The named connection of a provider, if it exists. */
    public Optional<IntegrationConnection> getConnection(String providerId, String connectionName) {
        return connectionRepository.findByProviderIdAndConnectionName(providerId, name(connectionName));
    }

    /** Every stored connection of a provider, {@code default} first, the others by name. */
    public List<IntegrationConnection> listConnections(String providerId) {
        return connectionRepository.findAllByProviderId(providerId).stream()
                .sorted(Comparator.comparing(IntegrationConnection::getConnectionName, IntegrationConnectionNames.ORDER))
                .toList();
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

    private static String name(String connectionName) {
        return IntegrationConnectionNames.normalize(connectionName);
    }
}
