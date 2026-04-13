package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationOAuthServiceTest {

    @Mock private IntegrationConnectionRepository connectionRepository;
    @Mock private IntegrationEncryptionService encryptionService;
    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationProvider provider;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
    }

    @Test
    void saveCredentials_forNonOAuthProvider_marksConnectionConnectedWhenValidationSucceeds() {
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
        IntegrationConnection existing = IntegrationConnection.builder()
                .providerId("trello")
                .build();

        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("api-key|api-token")).thenReturn("{\"name\":\"Trello User\"}");
        when(connectionRepository.findByProviderId("trello")).thenReturn(Optional.of(existing));
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
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
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
        when(connectionRepository.findByProviderId("google-calendar")).thenReturn(Optional.of(connection));
        when(encryptionService.decrypt("enc-client-id")).thenReturn("client-123");

        String url = service.getAuthorizationUrl(
                "google-calendar",
                "http://localhost/api/v1/integrations/google-calendar/callback",
                List.of("calendar.write", "not-allowed")
        );

        Map<String, String> params = parseQuery(URI.create(url));
        assertThat(url).startsWith("https://auth.example/authorize?");
        assertThat(params.get("client_id")).isEqualTo("client-123");
        assertThat(params.get("redirect_uri"))
                .isEqualTo("http://localhost/api/v1/integrations/google-calendar/callback");
        assertThat(params.get("scope")).isEqualTo("calendar.read calendar.write");
        assertThat(params.get("access_type")).isEqualTo("offline");
        assertThat(params.get("state")).isNotBlank();
    }

    @Test
    void handleCallback_persistsTokensScopesAndAccountInfo() throws Exception {
        server = startJsonServer(200, """
                {"access_token":"access-1","refresh_token":"refresh-1","expires_in":7200,"scope":"calendar.read calendar.write"}
                """);
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
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
        when(connectionRepository.findByProviderId("google-calendar")).thenReturn(Optional.of(connection));
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
    }

    @Test
    void handleCallback_withUnknownStateThrows() {
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);

        assertThatThrownBy(() -> service.handleCallback("code", "missing-state", "http://localhost/callback"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired OAuth state");
    }

    @Test
    void getValidAccessToken_returnsStoredTokenForNonOAuthProvider() {
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("trello")
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-access")
                .build();

        when(connectionRepository.findByProviderId("trello")).thenReturn(Optional.of(connection));
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(encryptionService.decrypt("enc-access")).thenReturn("token-value");

        String token = service.getValidAccessToken("trello");

        assertThat(token).isEqualTo("token-value");
    }

    @Test
    void getValidAccessToken_marksConnectionExpiredWhenRefreshTokenIsMissing() {
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("google-calendar")
                .status(ConnectionStatus.CONNECTED)
                .refreshTokenEncrypted(null)
                .tokenExpiry(Instant.now().minusSeconds(30))
                .build();

        when(connectionRepository.findByProviderId("google-calendar")).thenReturn(Optional.of(connection));
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
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
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

        when(connectionRepository.findByProviderId("google-calendar")).thenReturn(Optional.of(connection));
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
    void disconnect_forNonOAuthProviderDeletesConnectionWithoutRevocation() {
        IntegrationOAuthService service = new IntegrationOAuthService(
                connectionRepository, encryptionService, registry, objectMapper);
        IntegrationConnection connection = IntegrationConnection.builder()
                .providerId("trello")
                .status(ConnectionStatus.CONNECTED)
                .build();

        when(connectionRepository.findByProviderId("trello")).thenReturn(Optional.of(connection));
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);

        service.disconnect("trello");

        verify(connectionRepository).delete(connection);
    }

    private HttpServer startJsonServer(int statusCode, String responseBody) throws IOException {
        HttpServer httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/token", exchange -> respond(exchange, statusCode, responseBody));
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
}
