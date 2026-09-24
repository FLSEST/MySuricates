package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "integration_connections",
        uniqueConstraints = @UniqueConstraint(columnNames = {"provider_id", "connection_name"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class IntegrationConnection {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "provider_id", nullable = false)
    private String providerId;

    /** Name of this connection within its provider (V86); {@code default} for the pre-V86 single connection. */
    @Column(name = "connection_name", nullable = false)
    @Builder.Default
    private String connectionName = "default";

    @Column(nullable = false)
    @Builder.Default
    @Enumerated(EnumType.STRING)
    private ConnectionStatus status = ConnectionStatus.DISCONNECTED;

    @Column(name = "access_token_encrypted")
    private String accessTokenEncrypted;

    @Column(name = "refresh_token_encrypted")
    private String refreshTokenEncrypted;

    @Column(name = "token_expiry")
    private Instant tokenExpiry;

    private String scopes;

    @Column(name = "client_id_encrypted")
    private String clientIdEncrypted;

    @Column(name = "client_secret_encrypted")
    private String clientSecretEncrypted;

    @Column(name = "account_info", columnDefinition = "text")
    private String accountInfo;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;

    public enum ConnectionStatus {
        CONNECTED, DISCONNECTED, EXPIRED
    }
}
