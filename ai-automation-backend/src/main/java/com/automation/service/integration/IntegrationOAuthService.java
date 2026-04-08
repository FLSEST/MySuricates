package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class IntegrationOAuthService {

    private final IntegrationConnectionRepository connectionRepository;
    private final IntegrationEncryptionService encryptionService;
    private final IntegrationRegistry registry;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** Pending OAuth states for CSRF protection: state → providerId */
    private final ConcurrentHashMap<String, String> pendingStates = new ConcurrentHashMap<>();

    /**
     * Save OAuth client credentials (encrypted) for a provider.
     */
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
            try {
                String accountInfo = provider.fetchAccountInfo(combinedToken);
                if (accountInfo != null) {
                    connection.setAccountInfo(accountInfo);
                    connection.setStatus(ConnectionStatus.CONNECTED);
                } else {
                    log.warn("Credential validation returned no account info for provider {}", providerId);
                    connection.setStatus(ConnectionStatus.DISCONNECTED);
                }
            } catch (Exception e) {
                log.warn("Credential validation failed for provider {}: {}", providerId, e.getMessage());
                connection.setStatus(ConnectionStatus.DISCONNECTED);
            }
        }

        log.info("Saved client credentials for provider: {}", providerId);
        return connectionRepository.save(connection);
    }

    /**
     * Check if a provider has credentials configured (DB or env var).
     */
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

    /**
     * Generate an OAuth2 authorization URL for the given provider.
     */
    public String getAuthorizationUrl(String providerId, String redirectUri, java.util.List<String> additionalScopes) {
        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        String clientId = resolveClientId(providerId, config);
        if (clientId == null) {
            throw new IllegalStateException("No client credentials configured for provider: " + providerId);
        }

        String state = generateState();
        pendingStates.put(state, providerId);

        java.util.List<String> allScopes = new java.util.ArrayList<>(config.defaultScopes());
        if (additionalScopes != null) {
            additionalScopes.stream()
                    .filter(config.optionalScopes()::contains)
                    .forEach(allScopes::add);
        }
        String scopes = String.join(" ", allScopes);

        StringBuilder url = new StringBuilder(config.authorizationUrl())
                .append("?client_id=").append(urlEncode(clientId))
                .append("&redirect_uri=").append(urlEncode(redirectUri))
                .append("&response_type=code")
                .append("&scope=").append(urlEncode(scopes))
                .append("&state=").append(urlEncode(state));

        for (var entry : config.extraAuthParams().entrySet()) {
            url.append("&").append(urlEncode(entry.getKey())).append("=").append(urlEncode(entry.getValue()));
        }

        return url.toString();
    }

    /**
     * Handle the OAuth2 callback: exchange code for tokens and store them.
     */
    @Transactional
    public IntegrationConnection handleCallback(String code, String state, String redirectUri) {
        String providerId = pendingStates.remove(state);
        if (providerId == null) {
            throw new IllegalArgumentException("Invalid or expired OAuth state");
        }

        IntegrationProvider provider = registry.getProvider(providerId);
        OAuthConfig config = provider.getOAuthConfig();

        String clientId = resolveClientId(providerId, config);
        String clientSecret = resolveClientSecret(providerId, config);

        JsonNode tokenResponse = exchangeCodeForTokens(config.tokenUrl(), code, clientId, clientSecret, redirectUri);

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

    /**
     * Get a valid access token, refreshing if necessary.
     */
    public String getValidAccessToken(String providerId) {
        IntegrationConnection connection = connectionRepository.findByProviderId(providerId)
                .orElseThrow(() -> new IllegalStateException("No connection for provider: " + providerId));

        if (connection.getStatus() == ConnectionStatus.DISCONNECTED) {
            throw new IllegalStateException("Provider " + providerId + " is not connected");
        }

        // Non-OAuth providers use stored credentials directly (no expiry/refresh)
        IntegrationProvider provider = registry.getProvider(providerId);
        if (!provider.requiresOAuthFlow()) {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        }

        if (connection.getTokenExpiry() != null && connection.getTokenExpiry().isAfter(Instant.now().plusSeconds(300))) {
            return encryptionService.decrypt(connection.getAccessTokenEncrypted());
        }

        return refreshAccessToken(connection);
    }

    /**
     * Disconnect a provider: revoke token and delete connection.
     */
    @Transactional
    public void disconnect(String providerId) {
        connectionRepository.findByProviderId(providerId).ifPresent(connection -> {
            IntegrationProvider provider = registry.getProvider(providerId);
            // Only revoke tokens for OAuth providers (non-OAuth tokens like Trello API keys can't be revoked server-side)
            if (provider.requiresOAuthFlow()) {
                try {
                    String accessToken = encryptionService.decrypt(connection.getAccessTokenEncrypted());
                    revokeToken(accessToken);
                } catch (Exception e) {
                    log.warn("Failed to revoke token for {}: {}", providerId, e.getMessage());
                }
            }
            connectionRepository.delete(connection);
        });
        log.info("Disconnected integration: {}", providerId);
    }

    /**
     * Get the current connection status for a provider.
     */
    public Optional<IntegrationConnection> getConnection(String providerId) {
        return connectionRepository.findByProviderId(providerId);
    }

    private String refreshAccessToken(IntegrationConnection connection) {
        String refreshToken = encryptionService.decrypt(connection.getRefreshTokenEncrypted());
        if (refreshToken == null) {
            connection.setStatus(ConnectionStatus.EXPIRED);
            connectionRepository.save(connection);
            throw new IllegalStateException("No refresh token available for provider: " + connection.getProviderId());
        }

        IntegrationProvider provider = registry.getProvider(connection.getProviderId());
        OAuthConfig config = provider.getOAuthConfig();
        String clientId = resolveClientId(connection.getProviderId(), config);
        String clientSecret = resolveClientSecret(connection.getProviderId(), config);

        try {
            String body = "grant_type=refresh_token"
                    + "&refresh_token=" + urlEncode(refreshToken)
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(config.tokenUrl()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = objectMapper.readTree(response.body());

            if (response.statusCode() != 200) {
                log.error("Token refresh failed for {}: {} {}", connection.getProviderId(), response.statusCode(), response.body());
                connection.setStatus(ConnectionStatus.EXPIRED);
                connectionRepository.save(connection);
                throw new IllegalStateException("Token refresh failed: " + response.body());
            }

            String newAccessToken = json.get("access_token").asText();
            int expiresIn = json.has("expires_in") ? json.get("expires_in").asInt() : 3600;

            connection.setAccessTokenEncrypted(encryptionService.encrypt(newAccessToken));
            connection.setTokenExpiry(Instant.now().plusSeconds(expiresIn));
            connection.setStatus(ConnectionStatus.CONNECTED);

            if (json.has("refresh_token")) {
                connection.setRefreshTokenEncrypted(encryptionService.encrypt(json.get("refresh_token").asText()));
            }

            connectionRepository.save(connection);
            log.debug("Refreshed access token for {}", connection.getProviderId());
            return newAccessToken;

        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to refresh token for {}", connection.getProviderId(), e);
            connection.setStatus(ConnectionStatus.EXPIRED);
            connectionRepository.save(connection);
            throw new IllegalStateException("Token refresh failed", e);
        }
    }

    private JsonNode exchangeCodeForTokens(String tokenUrl, String code, String clientId, String clientSecret, String redirectUri) {
        try {
            String body = "grant_type=authorization_code"
                    + "&code=" + urlEncode(code)
                    + "&client_id=" + urlEncode(clientId)
                    + "&client_secret=" + urlEncode(clientSecret)
                    + "&redirect_uri=" + urlEncode(redirectUri);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(tokenUrl))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .timeout(Duration.ofSeconds(10))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new IllegalStateException("Token exchange failed: " + response.body());
            }

            return objectMapper.readTree(response.body());
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to exchange authorization code", e);
        }
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

    private void revokeToken(String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://oauth2.googleapis.com/revoke?token=" + urlEncode(token)))
                    .POST(HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(10))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            log.warn("Token revocation failed: {}", e.getMessage());
        }
    }

    private static String generateState() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
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
