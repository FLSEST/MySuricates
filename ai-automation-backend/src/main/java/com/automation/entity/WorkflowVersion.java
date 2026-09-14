package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "workflow_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class WorkflowVersion {

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

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Column(nullable = false, columnDefinition = "text")
    private String snapshot;

    @Column(name = "change_source", nullable = false)
    @Builder.Default
    private String changeSource = "user";

    @Column(name = "change_message")
    private String changeMessage;

    /** SHA-256 of {@link #snapshot} in canonical form (see WorkflowDefinitionHasher). */
    @Column(name = "definition_hash")
    private String definitionHash;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
