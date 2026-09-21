package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IntegrationOAuthConnectFlowTest {

    /** Env var names that are never set, so the env fallback deterministically yields null. */
    private static final OAuthConfig CONFIG = new OAuthConfig(
            "https://auth.example.com/authorize", "https://auth.example.com/token",
            List.of("read", "profile"), "MYSURICATES_TEST_UNSET_CLIENT_ID", "MYSURICATES_TEST_UNSET_SECRET");

    @Mock private IntegrationConnectionRepository connectionRepository;
    @Mock private IntegrationEncryptionService encryptionService;
    @Mock private IntegrationRegistry registry;
    @Mock private IntegrationProvider provider;
    @Mock private OAuthTokenClient tokenClient;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private TransactionStatus transactionStatus;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private IntegrationOAuthConnectFlow flow;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(transactionStatus);
        lenient().when(encryptionService.encrypt(anyString())).thenAnswer(inv -> "enc(" + inv.getArgument(0) + ")");
        lenient().when(encryptionService.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s.substring(4, s.length() - 1);
        });
        lenient().when(connectionRepository.save(any(IntegrationConnection.class))).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(registry.getProvider("acme")).thenReturn(provider);
        flow = new IntegrationOAuthConnectFlow(connectionRepository, encryptionService, registry, tokenClient,
                new TransactionTemplate(transactionManager));
    }

    private JsonNode json(String raw) throws Exception {
        return objectMapper.readTree(raw);
    }

    // ── saveCredentials ──────────────────────────────────────────────

    @Test
    void saveCredentials_oauthProvider_storesClientCredentialsOnlyAndNeverCallsProvider() {
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.empty());

        IntegrationConnection saved = flow.saveCredentials("acme", "cid", "secret");

        assertThat(saved.getProviderId()).isEqualTo("acme");
        assertThat(saved.getClientIdEncrypted()).isEqualTo("enc(cid)");
        assertThat(saved.getClientSecretEncrypted()).isEqualTo("enc(secret)");
        assertThat(saved.getAccessTokenEncrypted()).isNull();
        assertThat(saved.getStatus()).as("OAuth still needs the browser round-trip").isEqualTo(ConnectionStatus.DISCONNECTED);
        verify(provider, never()).fetchAccountInfo(anyString());
    }

    @Test
    void saveCredentials_patRejectedByProvider_staysDisconnectedWithoutScopeProbe() {
        IntegrationConnection existing = IntegrationConnection.builder().providerId("acme")
                .status(ConnectionStatus.CONNECTED).accountInfo("old").build();
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("key|token")).thenThrow(new IllegalStateException("401"));
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.of(existing));

        IntegrationConnection saved = flow.saveCredentials("acme", "key", "token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc(key|token)");
        verify(provider, never()).fetchGrantedScopes(anyString());
    }

    @Test
    void saveCredentials_patAccepted_recordsGrantedScopes() {
        when(provider.requiresOAuthFlow()).thenReturn(false);
        when(provider.fetchAccountInfo("key|token")).thenReturn("flo");
        when(provider.fetchGrantedScopes("key|token")).thenReturn("repo");
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.empty());

        IntegrationConnection saved = flow.saveCredentials("acme", "key", "token");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getAccountInfo()).isEqualTo("flo");
        assertThat(saved.getScopes()).isEqualTo("repo");
    }

    // ── handleCallback ───────────────────────────────────────────────

    @Test
    void handleCallback_exchangesWithDecryptedCredentials_outsideAnyTransaction() throws Exception {
        IntegrationConnection row = IntegrationConnection.builder().providerId("acme")
                .clientIdEncrypted("enc(cid)").clientSecretEncrypted("enc(sec)").build();
        when(provider.getOAuthConfig()).thenReturn(CONFIG);
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.of(row));
        when(tokenClient.exchangeCodeForTokens("acme", CONFIG, "code-1", "cid", "sec", "https://app/cb"))
                .thenReturn(json("{\"access_token\":\"at\",\"refresh_token\":\"rt\",\"expires_in\":120,\"scope\":\"read write\"}"));
        when(provider.fetchAccountInfo("at")).thenReturn("flo@acme");

        Instant before = Instant.now();
        IntegrationConnection saved = flow.handleCallback("acme", "code-1", "https://app/cb");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getAccessTokenEncrypted()).isEqualTo("enc(at)");
        assertThat(saved.getRefreshTokenEncrypted()).isEqualTo("enc(rt)");
        assertThat(saved.getScopes()).isEqualTo("read write");
        assertThat(saved.getAccountInfo()).isEqualTo("flo@acme");
        assertThat(saved.getTokenExpiry()).isBetween(before.plusSeconds(120), Instant.now().plusSeconds(120));

        // read burst committed → HTTP → write burst: the provider call never holds the connection
        InOrder order = inOrder(transactionManager, tokenClient, connectionRepository);
        order.verify(transactionManager).getTransaction(any());
        order.verify(transactionManager).commit(transactionStatus);
        order.verify(tokenClient).exchangeCodeForTokens(any(), any(), any(), any(), any(), any());
        order.verify(transactionManager).getTransaction(any());
        order.verify(connectionRepository).save(any());
        order.verify(transactionManager).commit(transactionStatus);
    }

    @Test
    void handleCallback_minimalTokenResponse_usesDefaultsAndKeepsExistingRefreshToken() throws Exception {
        IntegrationConnection row = IntegrationConnection.builder().providerId("acme")
                .refreshTokenEncrypted("enc(old-rt)").build();
        when(provider.getOAuthConfig()).thenReturn(CONFIG);
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.of(row));
        when(tokenClient.exchangeCodeForTokens("acme", CONFIG, "c", null, null, "https://app/cb"))
                .thenReturn(json("{\"access_token\":\"at\"}"));
        when(provider.fetchAccountInfo("at")).thenThrow(new RuntimeException("profile endpoint down"));

        Instant before = Instant.now();
        IntegrationConnection saved = flow.handleCallback("acme", "c", "https://app/cb");

        assertThat(saved.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(saved.getRefreshTokenEncrypted()).isEqualTo("enc(old-rt)");
        assertThat(saved.getScopes()).as("falls back to the configured default scopes").isEqualTo("read profile");
        assertThat(saved.getAccountInfo()).isNull();
        assertThat(Duration.between(before, saved.getTokenExpiry()).toSeconds()).isBetween(3599L, 3601L);
    }

    @Test
    void handleCallback_noAccessToken_failsWithoutWriting() throws Exception {
        when(provider.getOAuthConfig()).thenReturn(CONFIG);
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.empty());
        when(tokenClient.exchangeCodeForTokens(any(), any(), any(), any(), any(), any()))
                .thenReturn(json("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> flow.handleCallback("acme", "bad", "https://app/cb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no access_token");
        verify(connectionRepository, never()).save(any());
    }

    // ── credential resolution ────────────────────────────────────────

    @Test
    void resolveClientCredentials_prefersDb_elseEnvironmentVariable() {
        OAuthConfig envBacked = new OAuthConfig("a", "t", List.of(), "PATH", "MYSURICATES_TEST_UNSET_SECRET");
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.of(
                IntegrationConnection.builder().providerId("acme").clientSecretEncrypted("enc(db-secret)").build()));

        IntegrationOAuthConnectFlow.ClientCredentials credentials = flow.resolveClientCredentials("acme", envBacked);

        assertThat(credentials.clientId()).as("no DB value → env var").isEqualTo(System.getenv("PATH"));
        assertThat(credentials.clientSecret()).isEqualTo("db-secret");
    }

    @Test
    void resolveClientId_noRowAndNoEnv_isNull() {
        when(connectionRepository.findByProviderId("acme")).thenReturn(Optional.empty());

        assertThat(flow.resolveClientId("acme", CONFIG)).isNull();
    }
}
