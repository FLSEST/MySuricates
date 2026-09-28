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
     * Liegt der {@code ruleKey} ausserhalb des Katalogs ({@code audit/rule-keys.json})?
     *
     * <p>Dann ist die Regel-Identitaet dieses Findings ungeprueft: Sein Fingerprint haengt an einer
     * Schreibweise, die kein Katalog bestaetigt, und beim naechsten Lauf mit anderer Schreibweise
     * entsteht ein Wiedergaenger statt eines Wiedersehens. Die Ingestion setzt das Flag bei jedem
     * Ingest neu — auch zurueck auf {@code false}, sobald der Katalog den Key kennt (TODO ffa6d846).
     */
    /**
     * <b>Historisch.</b> Seit TODO f57945e2 wird ein Report mit unbekanntem {@code ruleKey}
     * komplett verworfen, sofern für sein Audit ein Katalog existiert — und nur dann kann ein Key
     * überhaupt als unbekannt gelten. Neue Findings mit diesem Flag entstehen also nicht mehr; das
     * Feld trägt weiter die Markierungen aus der Zeit, als ein gedrifteter Key übernommen und nur
     * angezeigt wurde (V75, TODO ffa6d846). Das Audit Center zeigt sie unverändert an.
     */
    @Column(name = "unknown_rule_key", nullable = false)
    @Builder.Default
    private boolean unknownRuleKey = false;
    /**
     * Gesetzt, wenn die {@code evidence} dieses Findings keinen Messwert des Laufs nennt, aus dem
     * es stammt — erfundener oder aus einem frueheren Report recycelter Beleg.
     *
     * <p>Solche Findings werden gespeichert und bleiben sichtbar, erzeugen aber <b>kein</b>
     * verwaltetes TODO: Der Prompt verlangt einen Beleg, geprueft hat ihn bis dahin niemand
     * (TODO a94b56a0). Wie {@link #unknownRuleKey} bei jedem Ingest neu gesetzt, auch zurueck.
     */
    @Column(name = "unverified_evidence", nullable = false)
    @Builder.Default
    private boolean unverifiedEvidence = false;
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
