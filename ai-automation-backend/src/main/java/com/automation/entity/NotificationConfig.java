package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "notification_configs")
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class NotificationConfig {

    public enum ChannelType {
        WEBHOOK, DESKTOP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String name;

    @Builder.Default
    private boolean enabled = true;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel_type", nullable = false)
    @Builder.Default
    private ChannelType channelType = ChannelType.WEBHOOK;

    /** URL for WEBHOOK channel (Slack/Discord/Teams incoming webhook) */
    @Column(name = "webhook_url")
    private String webhookUrl;

    /** Null = global (fires for all workflows); non-null = only for this workflow */
    @Column(name = "workflow_id")
    private String workflowId;

    @Builder.Default
    @Column(name = "trigger_on_success")
    private boolean triggerOnSuccess = true;

    @Builder.Default
    @Column(name = "trigger_on_failure")
    private boolean triggerOnFailure = true;

    @Builder.Default
    @Column(name = "trigger_always")
    private boolean triggerAlways = false;

    /**
     * Optional message template. Supports variables:
     * {{workflow_name}}, {{status}}, {{duration}}, {{error_message}}
     */
    @Column(name = "message_template", columnDefinition = "text")
    private String messageTemplate;

    @CreatedDate
    @Column(updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
