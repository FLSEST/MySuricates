package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "audit_finding_occurrences", uniqueConstraints =
        @UniqueConstraint(name = "uq_audit_occurrence", columnNames = {"finding_id", "report_id"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditFindingOccurrence {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "finding_id", nullable = false)
    private String findingId;
    @Column(name = "report_id", nullable = false)
    private String reportId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AuditSeverity severity;
    @Column(nullable = false)
    private String title;
    @Column(columnDefinition = "text")
    private String description;
    @Column(columnDefinition = "text")
    private String evidence;
    @Column(columnDefinition = "text")
    private String tags;
    @Column(name = "observed_at", nullable = false)
    private Instant observedAt;
}
