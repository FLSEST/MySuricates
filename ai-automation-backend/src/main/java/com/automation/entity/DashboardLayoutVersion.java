package com.automation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** One saved state of the whole dashboard layout (all views), see DashboardLayoutVersionService. */
@Entity
@Table(name = "dashboard_layout_versions")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DashboardLayoutVersion {

    @Id
    private String id;

    @PrePersist
    private void ensureId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
    }

    @Column(name = "created_at", nullable = false)
    @Builder.Default
    private Instant createdAt = Instant.now();

    /** Name the saving browser gave itself — the platform has no user accounts. */
    @Column(name = "device_label", nullable = false)
    private String deviceLabel;

    /** Optional user-given name; named versions are never pruned. */
    @Column(name = "name")
    private String name;

    @Column(name = "format_version", nullable = false)
    private int formatVersion;

    @Column(name = "view_count", nullable = false)
    private int viewCount;

    /** SHA-256 (hex) of {@link #content}, used to skip saves that change nothing. */
    @Column(name = "content_hash", nullable = false)
    private String contentHash;

    /** Canonical layout JSON as produced by DashboardLayoutValidator. */
    @Column(nullable = false, columnDefinition = "text")
    private String content;
}
