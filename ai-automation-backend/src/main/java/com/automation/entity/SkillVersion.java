package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

/** One recorded change of a skill; written only by {@code SkillMutationGateway}. */
@Entity
@Table(name = "skill_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillVersion {

    public enum Operation { CREATE, UPDATE, DELETE }

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "skill_id", nullable = false)
    private String skillId;

    @Column(name = "version_number", nullable = false)
    private int versionNumber;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Operation operation;

    /** Canonical JSON of the skill after the change (for DELETE: the state that was deleted). */
    @Column(nullable = false, columnDefinition = "text")
    private String snapshot;

    /** SHA-256 of the executable content (see SkillContentHasher). */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    @Column(name = "change_source", nullable = false)
    private String changeSource;

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();
}
