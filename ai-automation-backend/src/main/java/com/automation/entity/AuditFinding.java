package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.Instant;

@Entity
@Table(name = "audit_findings", uniqueConstraints =
        @UniqueConstraint(name = "uq_audit_finding_fingerprint", columnNames = {"project_id", "audit_key", "fingerprint"}))
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditFinding {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;
    @Column(name = "project_id", nullable = false)
    private String projectId;
    @Column(name = "audit_key", nullable = false)
    private String auditKey;
    @Column(nullable = false)
    private String fingerprint;
    @Column(name = "rule_key", nullable = false)
    private String ruleKey;
    @Column(nullable = false)
    private String subject;
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
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private AuditFindingStatus status = AuditFindingStatus.OPEN;
    @Column(name = "first_seen_report_id", nullable = false)
    private String firstSeenReportId;
    @Column(name = "last_seen_report_id", nullable = false)
    private String lastSeenReportId;
    @Column(name = "first_seen_run_id", nullable = false)
    private String firstSeenRunId;
    @Column(name = "last_seen_run_id", nullable = false)
    private String lastSeenRunId;
    @Column(name = "first_seen_at", nullable = false)
    private Instant firstSeenAt;
    @Column(name = "last_seen_at", nullable = false)
    private Instant lastSeenAt;
    @Column(name = "occurrence_count", nullable = false)
    @Builder.Default
    private int occurrenceCount = 1;
    @Column(name = "regression_count", nullable = false)
    @Builder.Default
    private int regressionCount = 0;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "disposition_note", columnDefinition = "text")
    private String dispositionNote;
    /** Evidence-Text zum Zeitpunkt der Risiko-Akzeptanz — Vergleichsbasis der Verschlechterungsregel. */
    @Column(name = "disposition_evidence", columnDefinition = "text")
    private String dispositionEvidence;
    /** Zeitpunkt der Risiko-Akzeptanz — Basis des 180-Tage-Verfalls. */
    @Column(name = "disposition_at")
    private Instant dispositionAt;
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
