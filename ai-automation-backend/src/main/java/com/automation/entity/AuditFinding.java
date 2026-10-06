package com.automation.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.time.Instant;

@Entity
@Table(name = "audit_findings", uniqueConstraints =
        @UniqueConstraint(name = "uq_audit_finding_fingerprint", columnNames = {"project_id", "audit_key", "fingerprint"}))
@EntityListeners(AuditTimestampListener.class)
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
    /**
     * Is the {@code ruleKey} outside the catalog ({@code audit/rule-keys.json})?
     *
     * <p>Then the rule identity of this finding is unchecked: its fingerprint hangs on a
     * spelling that no catalog confirms, and on the next run with a different spelling
     * a revenant arises instead of a recurrence. Ingestion sets the flag anew on every
     * ingest - also back to {@code false} once the catalog knows the key (TODO ffa6d846).
     */
    /**
     * <b>Historical.</b> Since TODO f57945e2 a report with an unknown {@code ruleKey}
     * is discarded entirely if a catalog exists for its audit - and only then can a key
     * count as unknown at all. New findings with this flag therefore no longer arise; the
     * field still carries the markers from the time when a drifted key was adopted and only
     * displayed (V75, TODO ffa6d846). The Audit Center shows them unchanged.
     */
    @Column(name = "unknown_rule_key", nullable = false)
    @Builder.Default
    private boolean unknownRuleKey = false;
    /**
     * Set when the {@code evidence} of this finding names no measured value of the run it
     * comes from - an invented piece of evidence or one recycled from an earlier report.
     *
     * <p>Such findings are stored and stay visible, but produce <b>no</b>
     * managed TODO: the prompt demands evidence, nobody has checked it so far
     * (TODO a94b56a0). Like {@link #unknownRuleKey} set anew on every ingest, also back.
     */
    @Column(name = "unverified_evidence", nullable = false)
    @Builder.Default
    private boolean unverifiedEvidence = false;
    @Column(name = "resolved_at")
    private Instant resolvedAt;
    @Column(name = "disposition_note", columnDefinition = "text")
    private String dispositionNote;
    /** Evidence text at the time of risk acceptance - comparison basis of the worsening rule. */
    @Column(name = "disposition_evidence", columnDefinition = "text")
    private String dispositionEvidence;
    /** Time of risk acceptance - basis of the 180-day expiry. */
    @Column(name = "disposition_at")
    private Instant dispositionAt;
    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;
    @LastModifiedDate
    @Column(name = "updated_at")
    private Instant updatedAt;
}
