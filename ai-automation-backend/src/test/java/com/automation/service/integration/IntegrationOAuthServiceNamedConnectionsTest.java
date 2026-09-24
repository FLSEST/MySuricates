package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Several named connections per provider (0b3cbcc3): every method with a connection name works on
 * exactly that row, the name of an OAuth connection travels in the state, and the calls without a
 * name keep meaning {@code default} (covered by {@link IntegrationOAuthServiceTest}).
 */
@ExtendWith(MockitoExtension.class)
class IntegrationOAuthServiceNamedConnectionsTest {

    @Mock private IntegrationConnectionRepository connectionRepository;
    @Mock private IntegrationEncryptionService encryptionService;
    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationProvider provider;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private HttpServer server;

    @BeforeEach
    void stubTransactionManager() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    private IntegrationOAuthService newService() {
        return new IntegrationOAuthService(connectionRepository, encryptionService, registry,
                new OAuthTokenClient(new ObjectMapper()), new OAuthStateRegistry(), transactionManager);
    }

    private static IntegrationConnection row(String provider, String name) {
        return IntegrationConnection.builder().providerId(provider).connectionName(name).build();
    }

    @Test
    void saveCredentials_forANewName_createsItsOwnRow() {
        IntegrationOAuthService service = newService();
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("user|pat")).thenReturn("{\"name\":\"work\"}");
        when(connectionRepository.findByProviderIdAndConnectionName("github", "work")).thenReturn(Optional.empty());
        when(encryptionService.encrypt(any())).thenAnswer(inv -> "enc:" + inv.getArgument(0));
        when(connectionRepository.save(any(IntegrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationConnection saved = service.saveCredentials("github", "work", "user", "pat");

        assertThat(saved.getProviderId()).isEqualTo("github");
        assertThat(saved.getConnectionName()).isEqualTo("work");
        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc:user|pat");
        verify(connectionRepository, never()).findByProviderIdAndConnectionName("github", "default");
    }

    @Test
    void getValidAccessToken_readsOnlyTheNamedRow() {
        IntegrationOAuthService service = newService();
        IntegrationConnection work = row("trello", "work");
        work.setStatus(ConnectionStatus.CONNECTED);
        work.setAccessTokenEncrypted("enc-work");
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(connectionRepository.findByProviderIdAndConnectionName("trello", "work")).thenReturn(Optional.of(work));
        when(encryptionService.decrypt("enc-work")).thenReturn("work-token");

        assertThat(service.getValidAccessToken("trello", "work")).isEqualTo("work-token");
        verify(connectionRepository, never()).findByProviderIdAndConnectionName("trello", "default");
    }

    @Test
    void getValidAccessToken_missingNamedConnection_namesItInTheError() {
        IntegrationOAuthService service = newService();
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresCredentials()).thenReturn(true);
        when(connectionRepository.findByProviderIdAndConnectionName("github", "work")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getValidAccessToken("github", "work"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("No connection for provider: github/work");
    }

    @Test
    void blankName_meansDefault_andInvalidNamesAreRejected() {
        IntegrationOAuthService service = newService();
        when(connectionRepository.findByProviderIdAndConnectionName("github", "default")).thenReturn(Optional.empty());

        assertThat(service.getConnection("github", " ")).isEmpty();
        assertThatThrownBy(() -> service.getConnection("github", "Work"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid connection name");
        assertThatThrownBy(() -> service.disconnect("github", "../x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void disconnectAndDelete_touchOnlyTheNamedRow() {
        IntegrationOAuthService service = newService();
        IntegrationConnection work = row("trello", "work");
        work.setStatus(ConnectionStatus.CONNECTED);
        work.setAccessTokenEncrypted("enc");
        when(registry.getProvider("trello")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(connectionRepository.findByProviderIdAndConnectionName("trello", "work")).thenReturn(Optional.of(work));

        service.disconnect("trello", "work");
        assertThat(work.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(connectionRepository).save(work);

        service.deleteCredentials("trello", "work");
        verify(connectionRepository).delete(work);
        verify(connectionRepository, never()).findByProviderIdAndConnectionName("trello", "default");
    }

    @Test
    void isProviderConfigured_namedPatConnection_needsItsOwnCredentials() {
        IntegrationOAuthService service = newService();
        when(registry.getProvider("github")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(connectionRepository.findByProviderIdAndConnectionName("github", "work")).thenReturn(Optional.empty());

        assertThat(service.isProviderConfigured("github", "work")).isFalse();
        verify(connectionRepository, never()).findByProviderIdAndConnectionName("github", "default");
    }

    @Test
    void isProviderConfigured_namedOAuthConnection_usesTheDefaultAppCredentials() {
        IntegrationOAuthService service = newService();
        IntegrationConnection defaultRow = row("google-calendar", "default");
        defaultRow.setClientIdEncrypted("cid");
        defaultRow.setClientSecretEncrypted("csec");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "work")).thenReturn(Optional.empty());
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default"))
                .thenReturn(Optional.of(defaultRow));

        assertThat(service.isProviderConfigured("google-calendar", "work")).isTrue();
    }

    @Test
    void listConnections_putsDefaultFirst() {
        IntegrationOAuthService service = newService();
        when(connectionRepository.findAllByProviderId("github")).thenReturn(List.of(
                row("github", "zeta"), row("github", "default"), row("github", "alpha")));

        assertThat(service.listConnections("github")).extracting(IntegrationConnection::getConnectionName)
                .containsExactly("default", "alpha", "zeta");
    }

    @Test
    void oauth_nameTravelsInTheState_andTheCallbackFillsThatRow() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"work-access\",\"expires_in\":3600}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
        OAuthConfig config = new OAuthConfig("https://auth.example/authorize",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/token",
                List.of("calendar.read"), List.of(), "MYSURICATES_NONEXISTENT_ID", "MYSURICATES_NONEXISTENT_SECRET", Map.of());
        IntegrationConnection defaultRow = row("google-calendar", "default");
        defaultRow.setClientIdEncrypted("enc-cid");
        defaultRow.setClientSecretEncrypted("enc-csec");
        when(registry.getProvider("google-calendar")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "work")).thenReturn(Optional.empty());
        when(connectionRepository.findByProviderIdAndConnectionName("google-calendar", "default"))
                .thenReturn(Optional.of(defaultRow));
        when(encryptionService.decrypt("enc-cid")).thenReturn("client-id");
        when(encryptionService.decrypt("enc-csec")).thenReturn("client-secret");
        when(encryptionService.encrypt("work-access")).thenReturn("enc-work-access");
        when(connectionRepository.save(any(IntegrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));

        IntegrationOAuthService service = newService();
        String url = service.getAuthorizationUrl("google-calendar", "work", "http://localhost/cb", null, null);
        assertThat(url).doesNotContain("connection");
        String state = stateOf(url);

        IntegrationConnection saved = service.handleCallback("code-1", state, "http://localhost/cb");

        ArgumentCaptor<IntegrationConnection> captor = ArgumentCaptor.forClass(IntegrationConnection.class);
        verify(connectionRepository).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(saved);
        assertThat(saved.getConnectionName()).isEqualTo("work");
        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc-work-access");
        assertThat(defaultRow.getAccessTokenEncrypted()).as("default row untouched").isNull();
    }

    private static String stateOf(String url) {
        for (String pair : URI.create(url).getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv[0].equals("state")) return URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
        }
        throw new AssertionError("no state in " + url);
    }
}
