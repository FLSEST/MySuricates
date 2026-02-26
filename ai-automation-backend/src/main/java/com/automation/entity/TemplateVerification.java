package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "template_verifications")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TemplateVerification {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "template_id", nullable = false)
    private String templateId;

    @Column(name = "project_id", nullable = false)
    private String projectId;

    @Column(name = "last_run_id")
    private String lastRunId;

    @Column(name = "verified_at", nullable = false)
    private Instant verifiedAt;
}
