package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class IntegrationConnectionTest {

    // --- Builder defaults ---

    @Test
    void should_defaultStatusToDisconnected_when_usingBuilder() {
        IntegrationConnection conn = IntegrationConnection.builder().build();

        assertThat(conn.getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.DISCONNECTED);
    }

    @Test
    void should_defaultAllFieldsToNull_when_usingBuilder() {
        IntegrationConnection conn = IntegrationConnection.builder().build();

        assertThat(conn.getId()).isNull();
        assertThat(conn.getProviderId()).isNull();
        assertThat(conn.getAccessTokenEncrypted()).isNull();
        assertThat(conn.getRefreshTokenEncrypted()).isNull();
        assertThat(conn.getTokenExpiry()).isNull();
        assertThat(conn.getScopes()).isNull();
        assertThat(conn.getClientIdEncrypted()).isNull();
        assertThat(conn.getClientSecretEncrypted()).isNull();
        assertThat(conn.getAccountInfo()).isNull();
        assertThat(conn.getCreatedAt()).isNull();
        assertThat(conn.getUpdatedAt()).isNull();
    }

    // --- Getter/Setter roundtrips ---

    @Test
    void should_storeProviderId_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setProviderId("google-calendar");

        assertThat(conn.getProviderId()).isEqualTo("google-calendar");
    }

    @Test
    void should_storeAccessTokenEncrypted_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setAccessTokenEncrypted("enc-access-token-xyz");

        assertThat(conn.getAccessTokenEncrypted()).isEqualTo("enc-access-token-xyz");
    }

    @Test
    void should_storeRefreshTokenEncrypted_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setRefreshTokenEncrypted("enc-refresh-token-abc");

        assertThat(conn.getRefreshTokenEncrypted()).isEqualTo("enc-refresh-token-abc");
    }

    @Test
    void should_storeTokenExpiry_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();
        Instant expiry = Instant.parse("2025-12-31T23:59:59Z");

        conn.setTokenExpiry(expiry);

        assertThat(conn.getTokenExpiry()).isEqualTo(expiry);
    }

    @Test
    void should_storeClientIdEncrypted_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setClientIdEncrypted("enc-client-id-123");

        assertThat(conn.getClientIdEncrypted()).isEqualTo("enc-client-id-123");
    }

    @Test
    void should_storeClientSecretEncrypted_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setClientSecretEncrypted("enc-client-secret-456");

        assertThat(conn.getClientSecretEncrypted()).isEqualTo("enc-client-secret-456");
    }

    @Test
    void should_storeScopes_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setScopes("email,calendar,contacts");

        assertThat(conn.getScopes()).isEqualTo("email,calendar,contacts");
    }

    @Test
    void should_storeAccountInfo_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setAccountInfo("{\"email\":\"user@example.com\",\"name\":\"Test User\"}");

        assertThat(conn.getAccountInfo()).isEqualTo("{\"email\":\"user@example.com\",\"name\":\"Test User\"}");
    }

    @Test
    void should_storeStatus_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();

        conn.setStatus(IntegrationConnection.ConnectionStatus.CONNECTED);

        assertThat(conn.getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.CONNECTED);
    }

    // --- Audit fields ---

    @Test
    void should_defaultCreatedAtToNull_when_newInstance() {
        IntegrationConnection conn = new IntegrationConnection();

        assertThat(conn.getCreatedAt()).isNull();
    }

    @Test
    void should_defaultUpdatedAtToNull_when_newInstance() {
        IntegrationConnection conn = new IntegrationConnection();

        assertThat(conn.getUpdatedAt()).isNull();
    }

    @Test
    void should_storeCreatedAt_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");

        conn.setCreatedAt(created);

        assertThat(conn.getCreatedAt()).isEqualTo(created);
    }

    @Test
    void should_storeUpdatedAt_when_setViaSetter() {
        IntegrationConnection conn = new IntegrationConnection();
        Instant updated = Instant.parse("2024-06-15T12:00:00Z");

        conn.setUpdatedAt(updated);

        assertThat(conn.getUpdatedAt()).isEqualTo(updated);
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant expiry = Instant.parse("2025-12-31T23:59:59Z");
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant updated = Instant.parse("2024-06-15T12:00:00Z");

        IntegrationConnection conn = new IntegrationConnection(
                "conn-id", "google-calendar", "work",
                IntegrationConnection.ConnectionStatus.CONNECTED,
                "enc-access-token", "enc-refresh-token",
                expiry, "email,calendar",
                "enc-client-id", "enc-client-secret",
                "{\"email\":\"test@example.com\"}",
                created, updated
        );

        assertThat(conn.getId()).isEqualTo("conn-id");
        assertThat(conn.getProviderId()).isEqualTo("google-calendar");
        assertThat(conn.getConnectionName()).isEqualTo("work");
        assertThat(conn.getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.CONNECTED);
        assertThat(conn.getAccessTokenEncrypted()).isEqualTo("enc-access-token");
        assertThat(conn.getRefreshTokenEncrypted()).isEqualTo("enc-refresh-token");
        assertThat(conn.getTokenExpiry()).isEqualTo(expiry);
        assertThat(conn.getScopes()).isEqualTo("email,calendar");
        assertThat(conn.getClientIdEncrypted()).isEqualTo("enc-client-id");
        assertThat(conn.getClientSecretEncrypted()).isEqualTo("enc-client-secret");
        assertThat(conn.getAccountInfo()).isEqualTo("{\"email\":\"test@example.com\"}");
        assertThat(conn.getCreatedAt()).isEqualTo(created);
        assertThat(conn.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void should_nameTheConnectionDefault_when_builtOrCreatedWithoutName() {
        assertThat(IntegrationConnection.builder().providerId("github").build().getConnectionName()).isEqualTo("default");
        assertThat(new IntegrationConnection().getConnectionName()).isEqualTo("default");
    }

    @Test
    void should_defaultToTheDefaultConnectionName_when_builtOrCreatedWithoutOne() {
        assertThat(IntegrationConnection.builder().providerId("github").build().getConnectionName()).isEqualTo("default");
        assertThat(new IntegrationConnection().getConnectionName()).isEqualTo("default");
    }

    // --- ConnectionStatus enum ---

    @Test
    void should_haveAllConnectionStatusValues_when_checkingDeclaredOrder() {
        assertThat(IntegrationConnection.ConnectionStatus.values())
                .containsExactly(
                        IntegrationConnection.ConnectionStatus.CONNECTED,
                        IntegrationConnection.ConnectionStatus.DISCONNECTED,
                        IntegrationConnection.ConnectionStatus.EXPIRED
                );
    }

    @Test
    void should_resolveConnectionStatusViaValueOf_when_givenValidName() {
        assertThat(IntegrationConnection.ConnectionStatus.valueOf("CONNECTED"))
                .isEqualTo(IntegrationConnection.ConnectionStatus.CONNECTED);
        assertThat(IntegrationConnection.ConnectionStatus.valueOf("DISCONNECTED"))
                .isEqualTo(IntegrationConnection.ConnectionStatus.DISCONNECTED);
        assertThat(IntegrationConnection.ConnectionStatus.valueOf("EXPIRED"))
                .isEqualTo(IntegrationConnection.ConnectionStatus.EXPIRED);
    }

    @Test
    void should_throwException_when_connectionStatusValueOfReceivesInvalidName() {
        assertThatThrownBy(() -> IntegrationConnection.ConnectionStatus.valueOf("INVALID"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Builder with explicit status override ---

    @Test
    void should_useExplicitStatus_when_overridingBuilderDefault() {
        IntegrationConnection conn = IntegrationConnection.builder()
                .status(IntegrationConnection.ConnectionStatus.EXPIRED)
                .build();

        assertThat(conn.getStatus()).isEqualTo(IntegrationConnection.ConnectionStatus.EXPIRED);
    }
}
