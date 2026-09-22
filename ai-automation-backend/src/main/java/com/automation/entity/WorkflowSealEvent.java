package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/**
 * One sealing or unsealing, kept for good. The activity log is pruned (30 days, some levels
 * earlier) and {@code workflows.sealed_at} only ever holds the current state, so neither answers
 * "when was this sealed, when did someone release it, against which definition?" later on.
 * Entries are chained via {@link #prevEventHash}: a deleted or altered row breaks the chain.
 */
@Entity
@Table(name = "workflow_seal_events")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowSealEvent {

    public enum Action {
        SEALED,
        /** Skill pins added to a seal that predates them (definition unchanged). */
        PINS_ADDED,
        UNSEALED
    }

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "workflow_id", nullable = false)
    private String workflowId;

    /** Name at the time — the workflow may be renamed or deleted later. */
    @Column(name = "workflow_name")
    private String workflowName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    @Column(name = "occurred_at", nullable = false)
    @Builder.Default
    private Instant occurredAt = Instant.now();

    @Column(name = "definition_hash")
    private String definitionHash;

    /** Skill content hashes at that moment (JSON), null when the seal pinned none. */
    @Column(name = "skill_pins", columnDefinition = "text")
    private String skillPins;

    @Column(name = "signature")
    private String signature;

    @Column(name = "prev_event_hash")
    private String prevEventHash;

    @Column(name = "event_hash", nullable = false)
    private String eventHash;
}
