package com.automation.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.automation.service.integration.IntegrationEncryptionService;
import com.automation.service.integration.IntegrationOAuthService;
import com.automation.service.integration.IntegrationProvider;
import com.automation.service.integration.IntegrationRegistry;
import com.automation.service.integration.OAuthConfig;
import com.automation.service.integration.OAuthTokenClient;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Disconnecting an integration must not hold a transaction around the revocation call
 * (Audit finding "revoke inside @Transactional"): the provider round-trip has a 10 s timeout and
 * would pin the single HikariCP connection for that whole time. Both entry points therefore
 * persist first in a short {@code TransactionTemplate} burst and revoke afterwards. Runs against
 * the real transaction manager and asserts, from inside the mocked token client, that no
 * transaction is active — and that the row was already written.
 */
@SpringBootTest
@ActiveProfiles("integration")
class IntegrationOAuthDisconnectIntegrationTest {

    private static final String PROVIDER_ID = "google-calendar";

    @Autowired private IntegrationOAuthService oAuthService;
    @Autowired private IntegrationConnectionRepository connectionRepository;
    @Autowired private IntegrationEncryptionService encryptionService;
    @MockitoBean private OAuthTokenClient tokenClient;
    @MockitoBean private IntegrationRegistry registry;

    private final AtomicReference<Boolean> txDuringRevoke = new AtomicReference<>();
    private final AtomicReference<ConnectionStatus> statusDuringRevoke = new AtomicReference<>();

    @BeforeEach
    void stubProviderAndClient() {
        IntegrationProvider provider = mock(IntegrationProvider.class);
        when(provider.requiresOAuthFlow()).thenReturn(true);
        when(provider.getOAuthConfig()).thenReturn(new OAuthConfig(
                "https://accounts.google.com/o/oauth2/v2/auth",
                "https://oauth2.googleapis.com/token",
                List.of("calendar.events"),
                "GOOGLE_CLIENT_ID",
                "GOOGLE_CLIENT_SECRET",
                Map.of()
        ).withRevocationUrl(OAuthConfig.GOOGLE_REVOCATION_URL));
        when(registry.getProvider(PROVIDER_ID)).thenReturn(provider);

        doAnswer(invocation -> {
            txDuringRevoke.set(TransactionSynchronizationManager.isActualTransactionActive());
            statusDuringRevoke.set(connectionRepository.findByProviderId(PROVIDER_ID)
                    .map(IntegrationConnection::getStatus).orElse(null));
            return null;
        }).when(tokenClient).revoke(any(), any(), any());
    }

    @AfterEach
    void cleanUp() {
        connectionRepository.findByProviderId(PROVIDER_ID).ifPresent(connectionRepository::delete);
    }

    private void persistConnectedRow() {
        connectionRepository.save(IntegrationConnection.builder()
                .providerId(PROVIDER_ID)
                .status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted(encryptionService.encrypt("access-token-1"))
                .refreshTokenEncrypted(encryptionService.encrypt("refresh-token-1"))
                .clientIdEncrypted(encryptionService.encrypt("client-id"))
                .clientSecretEncrypted(encryptionService.encrypt("client-secret"))
                .scopes("calendar.events")
                .accountInfo("{\"email\":\"user@example.com\"}")
                .build());
    }

    @Test
    void disconnect_revokesOutsideTransaction_afterPersistingDisconnectedRow() {
        persistConnectedRow();

        oAuthService.disconnect(PROVIDER_ID);

        verify(tokenClient).revoke(eq(PROVIDER_ID), any(OAuthConfig.class), eq("access-token-1"));
        assertThat(txDuringRevoke.get()).isFalse();
        assertThat(statusDuringRevoke.get()).as("row is persisted before the revoke goes out")
                .isEqualTo(ConnectionStatus.DISCONNECTED);

        IntegrationConnection reloaded = connectionRepository.findByProviderId(PROVIDER_ID).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(ConnectionStatus.DISCONNECTED);
        assertThat(reloaded.getAccessTokenEncrypted()).isNull();
        assertThat(reloaded.getRefreshTokenEncrypted()).isNull();
        assertThat(reloaded.getTokenExpiry()).isNull();
        assertThat(reloaded.getScopes()).isNull();
        assertThat(reloaded.getAccountInfo()).isNull();
        assertThat(reloaded.getClientIdEncrypted()).as("credentials survive a disconnect").isNotNull();
    }

    @Test
    void deleteCredentials_revokesOutsideTransaction_afterRemovingTheRow() {
        persistConnectedRow();

        oAuthService.deleteCredentials(PROVIDER_ID);

        verify(tokenClient).revoke(eq(PROVIDER_ID), any(OAuthConfig.class), eq("access-token-1"));
        assertThat(txDuringRevoke.get()).isFalse();
        assertThat(statusDuringRevoke.get()).as("row is gone before the revoke goes out").isNull();
        assertThat(connectionRepository.findByProviderId(PROVIDER_ID)).isEmpty();
    }
}
