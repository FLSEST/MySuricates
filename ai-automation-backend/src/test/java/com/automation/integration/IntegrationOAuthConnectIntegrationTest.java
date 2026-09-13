package com.automation.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.IntegrationEncryptionService;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.OAuthStateRegistry;
import com.automation.service.integration.OAuthTokenClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Connecting an integration must not hold a transaction around the provider round-trips
 * (Audit-TODO 56e07e83: {@code saveCredentials}/{@code handleCallback} did HTTP inside
 * {@code @Transactional}): the token exchange, the account-info lookup and the PAT validation
 * all have a 10 s timeout and would pin the single HikariCP connection for that whole time.
 * Runs against the real transaction manager and asserts, from inside the mocked token client
 * and provider, that no transaction is active — and that the row is persisted afterwards.
 * Sibling of {@link IntegrationOAuthDisconnectIntegrationTest}.
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationOAuthConnectIntegrationTest {

    private static final String OAUTH_PROVIDER = "google-calendar";
    private static final String PAT_PROVIDER = "trello";

    @Autowired private IntegrationOAuthService oAuthService;
    @Autowired private IntegrationConnectionRepository connectionRepository;
    @Autowired private IntegrationEncryptionService encryptionService;
    @Autowired private OAuthStateRegistry stateRegistry;
    @MockitoBean private OAuthTokenClient tokenClient;
    @MockitoBean private IntegrationRegistry registry;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<Boolean> txDuringExchange = new AtomicReference<>();
    private final AtomicReference<Boolean> txDuringAccountInfo = new AtomicReference<>();
    private final AtomicReference<Boolean> txDuringScopes = new AtomicReference<>();

    private IntegrationProvider oauthProvider;
    private IntegrationProvider patProvider;

    @BeforeEach
    void stubProviders() throws Exception {
        oauthProvider = mock(IntegrationProvider.class);
        when(oauthProvider.requiresOAuthFlow()).thenReturn(true);
        when(oauthProvider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("calendar.events"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of()
        ));
        when(oauthProvider.fetchAccountInfo(any())).thenAnswer(invocation -> {
            txDuringAccountInfo.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "{\"email\":\"user@example.com\"}";
        });
        when(registry.getProvider(OAUTH_PROVIDER)).thenReturn(oauthProvider);

        patProvider = mock(IntegrationProvider.class);
        when(patProvider.requiresOAuthFlow()).thenReturn(false);
        when(patProvider.fetchAccountInfo(any())).thenAnswer(invocation -> {
            txDuringAccountInfo.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "{\"name\":\"Trello User\"}";
        });
        when(patProvider.fetchGrantedScopes(any())).thenAnswer(invocation -> {
            txDuringScopes.set(TransactionSynchronizationManager.isActualTransactionActive());
            return "read,write";
        });
        when(registry.getProvider(PAT_PROVIDER)).thenReturn(patProvider);

        when(tokenClient.exchangeCodeForTokens(any(), any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            txDuringExchange.set(TransactionSynchronizationManager.isActualTransactionActive());
            return objectMapper.readTree(
                    "{\"access_token\":\"access-1\",\"refresh_token\":\"refresh-1\",\"expires_in\":7200,\"scope\":\"calendar.events\"}");
        });
    }

    @AfterEach
    void cleanUp() {
        for (String providerId : List.of(OAUTH_PROVIDER, PAT_PROVIDER)) {
            connectionRepository.findByProviderId(providerId).ifPresent(connectionRepository::delete);
        }
    }

    @Test
    void handleCallback_exchangesAndFetchesAccountInfoOutsideTransaction_thenPersists() {
        connectionRepository.save(IntegrationConnection.builder()
                .providerId(OAUTH_PROVIDER)
                .status(ConnectionStatus.DISCONNECTED)
                .clientIdEncrypted(encryptionService.encrypt("client-id"))
                .clientSecretEncrypted(encryptionService.encrypt("client-secret"))
                .build());
        String state = stateRegistry.register(OAUTH_PROVIDER, null);

        IntegrationConnection saved = oAuthService.handleCallback("code-123", state, "http://localhost/callback");

        verify(tokenClient).exchangeCodeForTokens(eq(OAUTH_PROVIDER), any(OAuthConfig.class), eq("code-123"),
                eq("client-id"), eq("client-secret"), eq("http://localhost/callback"));
        assertThat(txDuringExchange.get()).as("no transaction during token exchange").isFalse();
        assertThat(txDuringAccountInfo.get()).as("no transaction during account-info lookup").isFalse();

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        IntegrationConnection reloaded = connectionRepository.findByProviderId(OAUTH_PROVIDER).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(encryptionService.decrypt(reloaded.getAccessTokenEncrypted())).isEqualTo("access-1");
        assertThat(encryptionService.decrypt(reloaded.getRefreshTokenEncrypted())).isEqualTo("refresh-1");
        assertThat(reloaded.getScopes()).isEqualTo("calendar.events");
        assertThat(reloaded.getAccountInfo()).contains("user@example.com");
        assertThat(reloaded.getTokenExpiry()).isNotNull();
        assertThat(reloaded.getClientIdEncrypted()).as("credentials survive the callback").isNotNull();
    }

    @Test
    void handleCallback_withoutRefreshToken_keepsThePreviouslyStoredOne() throws Exception {
        connectionRepository.save(IntegrationConnection.builder()
                .providerId(OAUTH_PROVIDER)
                .status(ConnectionStatus.EXPIRED)
                .clientIdEncrypted(encryptionService.encrypt("client-id"))
                .clientSecretEncrypted(encryptionService.encrypt("client-secret"))
                .refreshTokenEncrypted(encryptionService.encrypt("refresh-old"))
                .build());
        when(tokenClient.exchangeCodeForTokens(any(), any(), any(), any(), any(), any()))
                .thenReturn(objectMapper.readTree("{\"access_token\":\"access-2\",\"expires_in\":60}"));
        String state = stateRegistry.register(OAUTH_PROVIDER, null);

        oAuthService.handleCallback("code-456", state, "http://localhost/callback");

        IntegrationConnection reloaded = connectionRepository.findByProviderId(OAUTH_PROVIDER).orElseThrow();
        assertThat(encryptionService.decrypt(reloaded.getAccessTokenEncrypted())).isEqualTo("access-2");
        assertThat(encryptionService.decrypt(reloaded.getRefreshTokenEncrypted())).isEqualTo("refresh-old");
        assertThat(reloaded.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
    }

    @Test
    void saveCredentials_pat_validatesOutsideTransaction_thenPersistsConnectedRow() {
        IntegrationConnection saved = oAuthService.saveCredentials(PAT_PROVIDER, "api-key", "api-token");

        verify(patProvider).fetchAccountInfo("api-key|api-token");
        verify(patProvider).fetchGrantedScopes("api-key|api-token");
        assertThat(txDuringAccountInfo.get()).as("no transaction during PAT validation").isFalse();
        assertThat(txDuringScopes.get()).as("no transaction during scope lookup").isFalse();

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        IntegrationConnection reloaded = connectionRepository.findByProviderId(PAT_PROVIDER).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(encryptionService.decrypt(reloaded.getAccessTokenEncrypted())).isEqualTo("api-key|api-token");
        assertThat(encryptionService.decrypt(reloaded.getClientIdEncrypted())).isEqualTo("api-key");
        assertThat(reloaded.getScopes()).isEqualTo("read,write");
        assertThat(reloaded.getAccountInfo()).contains("Trello User");
    }

    @Test
    void saveCredentials_pat_validationFailure_stillPersistsDisconnectedRowWithCredentials() {
        when(patProvider.fetchAccountInfo(any())).thenThrow(new RuntimeException("401 from provider"));

        IntegrationConnection saved = oAuthService.saveCredentials(PAT_PROVIDER, "api-key", "bad-token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        IntegrationConnection reloaded = connectionRepository.findByProviderId(PAT_PROVIDER).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(encryptionService.decrypt(reloaded.getClientSecretEncrypted())).isEqualTo("bad-token");
        assertThat(reloaded.getScopes()).isNull();
        assertThat(reloaded.getAccountInfo()).isNull();
    }

    @Test
    void saveCredentials_oauthProvider_persistsWithoutTouchingTheProvider() {
        IntegrationConnection saved = oAuthService.saveCredentials(OAUTH_PROVIDER, "client-id", "client-secret");

        verify(oauthProvider, org.mockito.Mockito.never()).fetchAccountInfo(any());
        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        IntegrationConnection reloaded = connectionRepository.findByProviderId(OAUTH_PROVIDER).orElseThrow();
        assertThat(encryptionService.decrypt(reloaded.getClientIdEncrypted())).isEqualTo("client-id");
        assertThat(encryptionService.decrypt(reloaded.getClientSecretEncrypted())).isEqualTo("client-secret");
        assertThat(reloaded.getAccessTokenEncrypted()).isNull();
    }
}
