package com.automation.service.integration;

import com.automation.entity.IntegrationConnection;
import com.automation.entity.IntegrationConnection.ConnectionStatus;
import com.automation.repository.IntegrationConnectionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class IntegrationTokenRefresherTest {

    private final IntegrationConnectionRepository repository = mock(IntegrationConnectionRepository.class);
    private final IntegrationEncryptionService encryption = mock(IntegrationEncryptionService.class);
    private final IntegrationRegistry registry = mock(IntegrationRegistry.class);
    private final OAuthTokenClient tokenClient = mock(OAuthTokenClient.class);
    private final IntegrationOAuthConnectFlow connectFlow = mock(IntegrationOAuthConnectFlow.class);
    private final IntegrationProvider provider = mock(IntegrationProvider.class);
    private final ObjectMapper json = new ObjectMapper();
    private final OAuthConfig config = new OAuthConfig("https://a", "https://t", List.of("s"), "ID", "SECRET");
    private IntegrationTokenRefresher refresher;
    private IntegrationConnection connection;

    @BeforeEach
    void setUp() {
        refresher = new IntegrationTokenRefresher(repository, encryption, registry, tokenClient, connectFlow);
        connection = IntegrationConnection.builder()
                .providerId("jira").status(ConnectionStatus.CONNECTED)
                .accessTokenEncrypted("enc-old").refreshTokenEncrypted("enc-refresh").build();
        when(registry.getProvider("jira")).thenReturn(provider);
        when(provider.getOAuthConfig()).thenReturn(config);
        when(encryption.decrypt("enc-refresh")).thenReturn("refresh-1");
        when(connectFlow.resolveClientId(eq("jira"), any(), eq(config))).thenReturn("cid");
        when(connectFlow.resolveClientSecret(eq("jira"), any(), eq(config))).thenReturn("csecret");
    }

    private void tokenResponse(String jsonText) throws Exception {
        when(tokenClient.refreshAccessToken("jira", config, "refresh-1", "cid", "csecret"))
                .thenReturn(json.readTree(jsonText));
    }

    @Test
    void refresh_storesEncryptedTokensAndDefaultsExpiryToOneHour() throws Exception {
        tokenResponse("{\"access_token\":\"new\"}");
        when(encryption.encrypt("new")).thenReturn("enc-new");
        connection.setStatus(ConnectionStatus.EXPIRED);

        String token = refresher.refresh(connection);

        assertThat(token).isEqualTo("new");
        assertThat(connection.getAccessTokenEncrypted()).isEqualTo("enc-new");
        assertThat(connection.getRefreshTokenEncrypted()).isEqualTo("enc-refresh");
        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.CONNECTED);
        assertThat(connection.getTokenExpiry()).isBetween(Instant.now().plusSeconds(3500), Instant.now().plusSeconds(3700));
        verify(repository).save(connection);
    }

    @Test
    void refresh_usesExpiresInAndRotatedRefreshToken() throws Exception {
        tokenResponse("{\"access_token\":\"new\",\"expires_in\":60,\"refresh_token\":\"r2\"}");
        when(encryption.encrypt("new")).thenReturn("enc-new");
        when(encryption.encrypt("r2")).thenReturn("enc-r2");

        refresher.refresh(connection);

        assertThat(connection.getRefreshTokenEncrypted()).isEqualTo("enc-r2");
        assertThat(connection.getTokenExpiry()).isBefore(Instant.now().plusSeconds(120));
    }

    @Test
    void refresh_withoutRefreshToken_marksExpiredAndSkipsProvider() {
        when(encryption.decrypt("enc-refresh")).thenReturn(null);

        assertThatThrownBy(() -> refresher.refresh(connection))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("No refresh token available");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        verify(repository).save(connection);
        verify(tokenClient, never()).refreshAccessToken(any(), any(), any(), any(), any());
    }

    @Test
    void refresh_providerRejectsRefresh_marksExpiredAndRethrows() {
        when(tokenClient.refreshAccessToken("jira", config, "refresh-1", "cid", "csecret"))
                .thenThrow(new IllegalStateException("Token refresh failed: 400"));

        assertThatThrownBy(() -> refresher.refresh(connection))
                .isInstanceOf(IllegalStateException.class).hasMessage("Token refresh failed: 400");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        assertThat(connection.getAccessTokenEncrypted()).isEqualTo("enc-old");
    }

    @Test
    void refresh_responseWithoutAccessToken_marksExpired() throws Exception {
        tokenResponse("{\"expires_in\":10}");

        assertThatThrownBy(() -> refresher.refresh(connection))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("no access_token");

        assertThat(connection.getStatus()).isEqualTo(ConnectionStatus.EXPIRED);
        assertThat(connection.getAccessTokenEncrypted()).isEqualTo("enc-old");
    }
}
