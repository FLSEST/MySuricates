package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationOAuthServiceTest {

    @Mock private IntegrationConnectionRepository connectionRepository;
    @Mock private IntegrationEncryptionService encryptionService;
    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationProvider provider;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    /** What the loopback server saw on /revoke; null means revocation never went out. */
    private record RevokeRequest(String method, String contentType, String rawQuery, String body) {}
    private final AtomicReference<RevokeRequest> revokeRequest = new AtomicReference<>();

    @BeforeEach
    void stubTransactionManager() {
        // TransactionTemplate.execute() asks the manager for a status, runs the callback,
        // then commits — a mock status lets the callback run and makes commit/rollback no-ops.
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    private IntegrationOAuthService newService() {
        return new IntegrationOAuthService(connectionRepository, encryptionService, registry,
                new OAuthTokenClient(objectMapper), new OAuthStateRegistry(), transactionManager);
    }

    @Test
    void saveCredentials_forNonOAuthProvider_marksConnectionConnectedWhenValidationSucceeds() {
        IntegrationOAuthService service = newService();
        IntegrationConnection existing = IntegrationConnection.builder()
                .providerId("trello")
                .build();

        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("api-key|api-token")).thenReturn("{\"name\":\"Trello User\"}");
        when(connectionRepository.findByProviderIdAndConnectionName("trello", "default")).thenReturn(Optional.of(existing));
        when(encryptionService.encrypt("api-key")).thenReturn("enc-client");
        when(encryptionService.encrypt("api-token")).thenReturn("enc-secret");
        when(encryptionService.encrypt("api-key|api-token")).thenReturn("enc-access");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationConnection saved = service.saveCredentials("trello", "api-key", "api-token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getClientIdEncrypted()).isEqualTo("enc-client");
        assertThat(saved.getClientSecretEncrypted()).isEqualTo("enc-secret");
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc-access");
        assertThat(saved.getAccountInfo()).isEqualTo("{\"name\":\"Trello User\"}");
    }

    @Test
    void getAuthorizationUrl_includesDefaultOptionalScopesAndExtraParams() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .clientIdEncrypted("enc-client-id")
                .build();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://auth.example/authorize",
                "https://auth.example/token",
                List.of("calendar.read"),
                List.of("calendar.write", "calendar.admin"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of("access_type", "offline")
        ));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-123");

        String url = service.getAuthorizationUrl(
                "google-calendar",
                "http://localhost/api/v1/integrations/google-calendar/callback",
                List.of("calendar.write", "not-allowed"), "https://localhost:3000"
        );

        Map<String, String> params = parseQuery(URI.create(url));
        assertThat(url).startsWith("https://auth.example/authorize?");
        assertThat(params.get("client_id")).isEqualTo("client-123");
        assertThat(params.get("redirect_uri"))
                .isEqualTo("http://localhost/api/v1/integrations/google-calendar/callback");
        assertThat(params.get("scope")).isEqualTo("calendar.read calendar.write");
        assertThat(params.get("access_type")).isEqualTo("offline");
        assertThat(params.get("state")).isNotBlank();
        assertThat(service.getCallbackOrigin(params.get("state"))).isEqualTo("https://localhost:3000");
    }

    @Test
    void handleCallback_persistsTokensScopesAndAccountInfo() throws Exception {
        server = startJsonServer(200, """
                {"access_token":"access-1","refresh_token":"refresh-1","expires_in":7200,"scope":"calendar.read calendar.write"}
                """);
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();
        OAuthConfig config = new OAuthConfig(
                "https://auth.example/authorize",
                serverUrl("/token"),
                List.of("calendar.read"),
                List.of("calendar.write"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of()
        );

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(provider.fetchAccountInfo("access-1")).thenReturn("{\"email\":\"user@example.com\"}");
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(encryptionService.encrypt("access-1")).thenReturn("enc-access-1");
        when(encryptionService.encrypt("refresh-1")).thenReturn("enc-refresh-1");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String authorizationUrl = service.getAuthorizationUrl(
                "google-calendar",
                "http://localhost/callback",
                List.of("calendar.write")
        );
        String state = parseQuery(URI.create(authorizationUrl)).get("state");

        IntegrationConnection saved = service.handleCallback("code-123", state, "http://localhost/callback");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc-access-1");
        assertThat(saved.getRefreshTokenEncrypted()).isEqualTo("enc-refresh-1");
        assertThat(saved.getScopes()).isEqualTo("calendar.read calendar.write");
        assertThat(saved.getAccountInfo()).contains("user@example.com");
        assertThat(saved.getTokenExpiry()).isAfter(Instant.now().plusSeconds(7100));
        assertThatThrownBy(() -> service.getCallbackOrigin(state)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void handleCallback_withUnknownStateThrows() {
        IntegrationOAuthService service = newService();

        assertThatThrownBy(() -> service.handleCallback("code", "missing-state", "http://localhost/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired OAuth state");
    }

    @Test
    void getValidAccessToken_returnsStoredTokenForNonOAuthProvider() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("trello")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("trello", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(encryptionService.decrypt("enc-access")).thenReturn("token-value");

        String token = service.getValidAccessToken("trello");

        assertThat(token).isEqualTo("token-value");
    }

    @Test
    void getValidAccessToken_marksConnectionExpiredWhenRefreshTokenIsMissing() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .refreshTokenEncrypted(null)
                .tokenExpiry(Instant.now().minusSeconds(30))
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.getValidAccessToken("google-calendar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No refresh token available");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        verify(connectionRepository).save(connection);
    }

    @Test
    void getValidAccessToken_refreshesExpiredOAuthToken() throws Exception {
        server = startJsonServer(200, """
                {"access_token":"new-access","refresh_token":"new-refresh","expires_in":1800}
                """);
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .accessTokenEncrypted("enc-old-access")
                .refreshTokenEncrypted("enc-old-refresh")
                .tokenExpiry(Instant.now().minusSeconds(60))
                .build();
        OAuthConfig config = new OAuthConfig(
                "https://auth.example/authorize",
                serverUrl("/token"),
                List.of("calendar.read"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET"
        );

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(encryptionService.decrypt("enc-old-refresh")).thenReturn("refresh-123");
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(encryptionService.encrypt("new-access")).thenReturn("enc-new-access");
        when(encryptionService.encrypt("new-refresh")).thenReturn("enc-new-refresh");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        String token = service.getValidAccessToken("google-calendar");

        assertThat(token).isEqualTo("new-access");
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(connection.getAccessTokenEncrypted()).isEqualTo("enc-new-access");
        assertThat(connection.getRefreshTokenEncrypted()).isEqualTo("enc-new-refresh");
        assertThat(connection.getTokenExpiry()).isAfter(Instant.now().plusSeconds(1700));
    }

    @Test
    void getValidAccessToken_throwsWhenRefreshTokenIsInvalid() throws Exception {
        server = startJsonServer(400, """
                {"error":"invalid_grant","error_description":"Token has been expired or revoked."}
                """);
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .accessTokenEncrypted("enc-old-access")
                .refreshTokenEncrypted("enc-old-refresh")
                .tokenExpiry(Instant.now().minusSeconds(60))
                .build();
        OAuthConfig config = new OAuthConfig(
                "https://auth.example/authorize",
                serverUrl("/token"),
                List.of("calendar.read"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET"
        );

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(encryptionService.decrypt("enc-old-refresh")).thenReturn("refresh-123");
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        // The provider body reaches the client verbatim via GlobalExceptionHandler (409) — it must
        // carry the status only, never the provider's error payload.
        assertThatThrownBy(() -> service.getValidAccessToken("google-calendar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Token refresh failed")
                .hasMessageContaining("(HTTP 400)")
                .hasMessageNotContaining("invalid_grant")
                .hasMessageNotContaining("Token has been expired or revoked");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        verify(connectionRepository).save(connection);
    }

    @Test
    void getValidAccessToken_refreshResponseWithoutAccessToken_marksConnectionExpired() throws Exception {
        // A 200 that carries no access_token used to NPE inside the old catch-all (which then set
        // EXPIRED). Without an explicit guard the connection would stay CONNECTED with a stale
        // token and every following call would retry the same dead refresh.
        server = startJsonServer(200, "{}");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .accessTokenEncrypted("enc-old-access")
                .refreshTokenEncrypted("enc-old-refresh")
                .tokenExpiry(Instant.now().minusSeconds(60))
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(null));
        when(encryptionService.decrypt("enc-old-refresh")).thenReturn("refresh-123");
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.getValidAccessToken("google-calendar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token refresh failed for google-calendar (no access_token in response)");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        assertThat(connection.getAccessTokenEncrypted()).as("the stale token is not overwritten")
                .isEqualTo("enc-old-access");
        verify(connectionRepository).save(connection);
    }

    @Test
    void getValidAccessToken_refreshResponseWithMalformedBody_hidesBodyAndMarksExpired() throws Exception {
        server = startJsonServer(200, "<html><body>client_secret=s3cr3t</body></html>");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .accessTokenEncrypted("enc-old-access")
                .refreshTokenEncrypted("enc-old-refresh")
                .tokenExpiry(Instant.now().minusSeconds(60))
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(null));
        when(encryptionService.decrypt("enc-old-refresh")).thenReturn("refresh-123");
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        assertThatThrownBy(() -> service.getValidAccessToken("google-calendar"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token refresh failed for google-calendar (malformed response)")
                .hasMessageNotContaining("s3cr3t");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        verify(connectionRepository).save(connection);
    }

    @Test
    void getValidAccessToken_returnsCachedTokenWhenNotExpired() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .tokenExpiry(Instant.now().plusSeconds(600))
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(encryptionService.decrypt("enc-access")).thenReturn("valid-token");

        String token = service.getValidAccessToken("google-calendar");

        assertThat(token).isEqualTo("valid-token");
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void getValidAccessToken_credentialLessProvider_noConnection_returnsNull() {
        IntegrationOAuthService service = newService();

        when(registry.getProvider("taskwarrior")).thenReturn(provider);
        when(connectionRepository.findByProviderIdAndConnectionName("taskwarrior", "default")).thenReturn(Optional.empty());
        when(provider.requiresCredentials()).thenReturn(false);

        String token = service.getValidAccessToken("taskwarrior");

        assertThat(token).isNull();
    }

    @Test
    void getValidAccessToken_credentialLessProvider_decryptFails_returnsNull() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("taskwarrior")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-broken")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("taskwarrior", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("taskwarrior")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.requiresCredentials()).thenReturn(false);
        when(encryptionService.decrypt("enc-broken")).thenThrow(new IllegalStateException("Failed to decrypt token"));

        String token = service.getValidAccessToken("taskwarrior");

        assertThat(token).isNull();
    }

    @Test
    void getValidAccessToken_credentialLessProvider_decryptOk_returnsToken() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("taskwarrior")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("taskwarrior", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("taskwarrior")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(encryptionService.decrypt("enc-access")).thenReturn("/data|/rc");

        String token = service.getValidAccessToken("taskwarrior");

        assertThat(token).isEqualTo("/data|/rc");
    }

    @Test
    void getValidAccessToken_credentialProvider_decryptFails_stillThrows() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("github")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-broken")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("github", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.requiresCredentials()).thenReturn(true);
        when(encryptionService.decrypt("enc-broken")).thenThrow(new IllegalStateException("Failed to decrypt token"));

        assertThatThrownBy(() -> service.getValidAccessToken("github"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Failed to decrypt token");
    }

    @Test
    void disconnect_forNonOAuthProviderClearsTokensWithoutRevocation() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("trello")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("trello", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);

        service.disconnect("trello");

        verify(connectionRepository, never()).delete(any());
        verify(connectionRepository).save(connection);
        assertThat(connection.getAccessTokenEncrypted()).isNull();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.getClientIdEncrypted()).isEqualTo("enc-client-id");
        assertThat(connection.getClientSecretEncrypted()).isEqualTo("enc-client-secret");
    }

    @Test
    void disconnect_completesEvenWhenRevocationFails() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .refreshTokenEncrypted("enc-refresh")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(encryptionService.decrypt("enc-access")).thenThrow(new RuntimeException("decryption failed"));

        service.disconnect("google-calendar");

        verify(connectionRepository, never()).delete(any());
        verify(connectionRepository).save(connection);
        assertThat(connection.getAccessTokenEncrypted()).isNull();
        assertThat(connection.getRefreshTokenEncrypted()).isNull();
        assertThat(connection.getClientIdEncrypted()).isEqualTo("enc-client-id");
        assertThat(connection.getClientSecretEncrypted()).isEqualTo("enc-client-secret");
    }

    @Test
    void disconnect_noConnection_isNoop() {
        IntegrationOAuthService service = newService();
        when(connectionRepository.findByProviderIdAndConnectionName("trello", "default")).thenReturn(Optional.empty());

        service.disconnect("trello");

        verify(connectionRepository, never()).save(any());
        verify(connectionRepository, never()).delete(any());
    }

    @Test
    void deleteCredentials_removesEntireRow() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("trello")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("trello", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);

        service.deleteCredentials("trello");

        verify(connectionRepository).delete(connection);
    }

    @Test
    void disconnect_revokesGoogleTokenViaPostBody() throws Exception {
        server = startJsonServer(200, "{}");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = connectedGoogleConnection();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(serverUrl("/revoke")));
        when(encryptionService.decrypt("enc-access")).thenReturn("access-token-1");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.disconnect("google-calendar");

        RevokeRequest revoke = revokeRequest.get();
        assertThat(revoke).isNotNull();
        assertThat(revoke.method()).isEqualTo("POST");
        assertThat(revoke.contentType()).isEqualTo("application/x-www-form-urlencoded");
        assertThat(revoke.body()).isEqualTo("token=access-token-1");
        assertThat(revoke.rawQuery()).as("token must not leak into the query string").isNull();
        assertThat(connection.getAccessTokenEncrypted()).isNull();
        assertThat(connection.getRefreshTokenEncrypted()).isNull();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(connection.getClientIdEncrypted()).isEqualTo("enc-client-id");
        verify(connectionRepository).save(connection);
    }

    @Test
    void deleteCredentials_deletesRowAndRevokesToken() throws Exception {
        server = startJsonServer(200, "{}");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = connectedGoogleConnection();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(serverUrl("/revoke")));
        when(encryptionService.decrypt("enc-access")).thenReturn("access-token-1");

        service.deleteCredentials("google-calendar");

        verify(connectionRepository).delete(connection);
        assertThat(revokeRequest.get()).isNotNull();
        assertThat(revokeRequest.get().body()).isEqualTo("token=access-token-1");
    }

    @Test
    void disconnect_withoutRevocationUrl_skipsHttp() throws Exception {
        server = startJsonServer(200, "{}");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("jira")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .clientIdEncrypted("enc-client-id")
                .build();

        when(connectionRepository.findByProviderIdAndConnectionName("jira", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("jira")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        // Atlassian 3LO has no documented revocation endpoint → revocationUrl stays null.
        when(provider.getOAuthConfig()).thenReturn(googleConfig(null));
        when(encryptionService.decrypt("enc-access")).thenReturn("access-token-1");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        service.disconnect("jira");

        assertThat(revokeRequest.get()).isNull();
        assertThat(connection.getAccessTokenEncrypted()).isNull();
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
    }

    @Test
    void handleCallback_exchangeFailure_hidesProviderBody() throws Exception {
        server = startJsonServer(400, """
                {"error":"invalid_client","error_description":"client_secret=s3cr3t is wrong"}
                """);
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(null));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");

        String authorizationUrl = service.getAuthorizationUrl(
                "google-calendar", "http://localhost/callback", List.of());
        String state = parseQuery(URI.create(authorizationUrl)).get("state");

        // GlobalExceptionHandler turns this into a 409 carrying the message verbatim.
        assertThatThrownBy(() -> service.handleCallback("code-123", state, "http://localhost/callback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token exchange failed for google-calendar (HTTP 400)")
                .hasMessageNotContaining("s3cr3t")
                .hasMessageNotContaining("invalid_client");
        verify(connectionRepository, never()).save(any());
    }

    @Test
    void handleCallback_withoutAccessTokenInResponse_throwsInsteadOfNpe() throws Exception {
        // A 200 that carries no access_token used to NPE inside tokenResponse.get(...).asText().
        server = startJsonServer(200, "{}");
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(googleConfig(null));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");

        String authorizationUrl = service.getAuthorizationUrl(
                "google-calendar", "http://localhost/callback", List.of());
        String state = parseQuery(URI.create(authorizationUrl)).get("state");

        assertThatThrownBy(() -> service.handleCallback("code-123", state, "http://localhost/callback"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Token exchange failed for google-calendar (no access_token in response)");
        verify(connectionRepository, never()).save(any());
    }

    private IntegrationConnection connectedGoogleConnection() {
        return IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .refreshTokenEncrypted("enc-refresh")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();
    }

    private OAuthConfig googleConfig(String revocationUrl) {
        return new OAuthConfig(
                "https://auth.example/authorize",
                serverUrl("/token"),
                List.of("calendar.read"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET"
        ).withRevocationUrl(revocationUrl);
    }

    @Test
    void getAuthorizationUrl_throwsWhenProviderNotConfigured() {
        IntegrationOAuthService service = newService();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://auth.example/authorize",
                "https://auth.example/token",
                List.of("calendar.read"),
                "MYSURICATES_NONEXISTENT_CLIENT_ID_XYZ",
                "MYSURICATES_NONEXISTENT_CLIENT_SECRET_XYZ"
        ));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getAuthorizationUrl(
                "google-calendar", "http://localhost/callback", List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No client credentials configured for provider");
    }

    @Test
    void isProviderConfigured_returnsTrueWhenCredentialsExist() {
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .build();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://auth.example/authorize",
                "https://auth.example/token",
                List.of("calendar.read"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET"
        ));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));

        assertThat(service.isProviderConfigured("google-calendar")).isTrue();
    }

    @Test
    void isProviderConfigured_returnsFalseWhenNoConnection() {
        IntegrationOAuthService service = newService();

        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://auth.example/authorize",
                "https://auth.example/token",
                List.of("calendar.read"),
                "MYSURICATES_NONEXISTENT_CLIENT_ID_XYZ",
                "MYSURICATES_NONEXISTENT_CLIENT_SECRET_XYZ"
        ));
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.empty());

        assertThat(service.isProviderConfigured("google-calendar")).isFalse();
    }

    @Test
    void getConnection_returnsEmptyWhenNoConnectionExists() {
        IntegrationOAuthService service = newService();

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.empty());

        Optional<IntegrationConnection> result = service.getConnection("google-calendar");

        assertThat(result).isEmpty();
    }

    @Test
    void concurrentRefreshRequests_resultInConsistentState() throws Exception {
        // No internal locking in IntegrationOAuthService. Both threads may both trigger a refresh
        // (double-refresh) or one may use the cached result of the other — both outcomes return
        // a valid token. This test documents the current is-behavior (no double-refresh guard).
        server = startJsonServer(200, """
                {"access_token":"new-access","expires_in":1800}
                """);
        IntegrationOAuthService service = newService();
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .clientIdEncrypted("enc-client-id")
                .clientSecretEncrypted("enc-client-secret")
                .accessTokenEncrypted("enc-old-access")
                .refreshTokenEncrypted("enc-old-refresh")
                .tokenExpiry(Instant.now().minusSeconds(60))
                .build();
        OAuthConfig config = new OAuthConfig(
                "https://auth.example/authorize",
                serverUrl("/token"),
                List.of("calendar.read"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET"
        );

        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default")).thenReturn(Optional.of(connection));
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(encryptionService.decrypt("enc-old-refresh")).thenReturn("refresh-123");
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-client-secret")).thenReturn("client-secret");
        when(encryptionService.encrypt("new-access")).thenReturn("enc-new-access");
        // The second thread may take the cached-token path if the first thread completes first
        lenient().when(encryptionService.decrypt("enc-new-access")).thenReturn("new-access");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        CountDownLatch startLatch = new CountDownLatch(1);
        CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return service.getValidAccessToken("google-calendar");
        });
        CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> {
            try { startLatch.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
            return service.getValidAccessToken("google-calendar");
        });
        startLatch.countDown();

        String token1 = future1.get(15, TimeUnit.SECONDS);
        String token2 = future2.get(15, TimeUnit.SECONDS);

        assertThat(token1).isEqualTo("new-access");
        assertThat(token2).isEqualTo("new-access");
        verify(connectionRepository, atLeast(1)).save(any(IntegrationConnection.class));
    }

    private HttpServer startJsonServer(int statusCode, String responseBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/token", exchange -> respond(exchange, statusCode, responseBody));
        httpServer.createContext("/revoke", exchange -> {
            revokeRequest.set(new RevokeRequest(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Content-Type"),
                    exchange.getRequestURI().getRawQuery(),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            respond(exchange, 200, "{}");
        });
        httpServer.start();
        return httpServer;
    }

    private String serverUrl(String path) {
        return "http://127.0.0.1:" + server.getAddress().getPort() + path;
    }

    private static void respond(HttpExchange exchange, int statusCode, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(statusCode, bytes.length);
        try (OutputStream output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }

    private static Map<String, String> parseQuery(URI uri) {
        Map<String, String> values = new HashMap<>();
        String rawQuery = uri.getRawQuery();
        if (rawQuery == null || rawQuery.isBlank()) {
            return values;
        }
        for (String entry : rawQuery.split("&")) {
            String[] parts = entry.split("=", 2);
            String key = URLDecoder.decode(parts[0], StandardCharsets.UTF_8);
            String value = parts.length > 1
                    ? URLDecoder.decode(parts[1], StandardCharsets.UTF_8)
                    : "";
            values.put(key, value);
        }
        return values;
    }

    // ── PAT scopes (TODO 62705e0b) ─────────────────────────────────────────────────

    private IntegrationOAuthService serviceForPat(String scopesFromProvider) {
        IntegrationOAuthService service = newService();
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("x|ghp_token")).thenReturn("{\"login\":\"alex\"}");
        when(provider.fetchGrantedScopes("x|ghp_token")).thenReturn(scopesFromProvider);
        when(connectionRepository.findByProviderIdAndConnectionName("github", "default")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(any())).thenReturn("enc");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        return service;
    }

    @Test
    void saveCredentials_pat_persistsGrantedScopesReportedByProvider() {
        IntegrationConnection saved = serviceForPat("repo read:org").saveCredentials("github", "x", "ghp_token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getScopes()).isEqualTo("repo read:org");
    }

    @Test
    void saveCredentials_pat_unknownScopesStayNull() {
        IntegrationConnection saved = serviceForPat(null).saveCredentials("github", "x", "ghp_token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getScopes()).isNull();
    }

    @Test
    void saveCredentials_pat_validationFailure_doesNotAskForScopes() {
        IntegrationOAuthService service = newService();
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("x|bad")).thenReturn(null);
        when(connectionRepository.findByProviderIdAndConnectionName("github", "default")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(any())).thenReturn("enc");
        when(connectionRepository.save(any(IntegrationConnection.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));

        IntegrationConnection saved = service.saveCredentials("github", "x", "bad");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(provider, never()).fetchGrantedScopes(any());
    }
}
