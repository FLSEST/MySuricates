package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * Uebernimmt die Findings eines gueltigen Audit-Reports in den Bestand: gleicht jedes Finding per
 * Fingerprint ab, oeffnet oder bewertet bekannte neu, haelt Vorkommen und verwaltete TODOs nach und
 * setzt auf RESOLVED, was der Snapshot nicht mehr enthaelt.
 *
 * <p>Umzug aus {@code AuditIngestionService} (Audit-TODO aac5fbf7): Der Service prueft und speichert
 * den Report, hier wird er angewendet — innerhalb der Transaktion von
 * {@link AuditIngestionService#ingest}, eigene Transaktionsgrenzen gibt es hier bewusst nicht.
 * Rumpf von {@code ingestSnapshot} (hier {@link #apply}) und Activity-Log-Texte unveraendert, samt
 * Quelle {@code "AuditIngestionService"}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
class AuditSnapshotApplier {
    private final AuditFindingRepository findingRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final AuditAcceptancePolicy acceptancePolicy;
    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final ActivityLogService activityLog;
    private final ManagedTodoWriter managedTodoWriter;

    void apply(WorkflowRunCompletedEvent event, AuditReport report,
               List<ParsedFinding> parsedFindings, Set<String> factNames) {
        Instant observedAt = report.getGeneratedAt();
        Set<String> seenFingerprints = new HashSet<>();
        Set<String> reportedRuleKeys = new HashSet<>();
        List<AuditFinding> touched = new ArrayList<>();
        List<String> collisions = new ArrayList<>();

        for (ParsedFinding item : parsedFindings) {
            AuditRuleKeyCatalog.Resolution resolution = resolveRuleKey(event, report, item, reportedRuleKeys);
            String ruleKey = resolution.ruleKey();
            String fingerprint = fingerprint(event.getProjectId(), report.getAuditKey(), ruleKey, item.subject());
            if (!seenFingerprints.add(fingerprint)) {
                collisions.add(item.subject());
                continue;
            }

            AuditFinding finding = findingRepository
                    .findByProjectIdAndAuditKeyAndFingerprint(event.getProjectId(), report.getAuditKey(), fingerprint)
                    .orElseGet(() -> AuditFinding.builder()
                            .projectId(event.getProjectId()).auditKey(report.getAuditKey()).fingerprint(fingerprint)
                            .ruleKey(ruleKey).subject(item.subject()).status(AuditFindingStatus.OPEN)
                            .firstSeenReportId(report.getId()).firstSeenRunId(event.getRunId()).firstSeenAt(observedAt)
                            .occurrenceCount(0).build());

            if (finding.getId() != null) {
                if (finding.getStatus() == AuditFindingStatus.RESOLVED) {
                    finding.setRegressionCount(finding.getRegressionCount() + 1);
                }
                if (finding.getStatus() == AuditFindingStatus.RESOLVED
                        || finding.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION) {
                    finding.setStatus(AuditFindingStatus.OPEN);
                    finding.setResolvedAt(null);
                }
                AuditAcceptancePolicy.Outcome outcome = acceptancePolicy.reassess(finding, item.evidence(), observedAt);
                if (outcome != AuditAcceptancePolicy.Outcome.UNCHANGED) {
                    log.info("Accepted audit finding {} ({}) re-assessed: {}", finding.getId(), finding.getTitle(), outcome);
                }
            }
            // subject ist reines Anzeigeattribut — der neueste Wert gewinnt, der Fingerprint haengt
            // an seiner normalisierten Form und bleibt dadurch ueber Drift hinweg stabil.
            finding.setSubject(item.subject());
            // Bei jedem Ingest neu gesetzt, in beide Richtungen: ergaenzt der Katalog den Key,
            // faellt die Markierung beim naechsten Lauf von selbst weg (TODO ffa6d846).
            finding.setUnknownRuleKey(resolution.unknown());
            // Ebenso bei jedem Ingest neu: nennt der Beleg einen Messwert dieses Laufs? (a94b56a0)
            boolean unverified = !EvidenceVerification.isSupported(item.evidence(), factNames);
            if (unverified && !finding.isUnverifiedEvidence()) {
                activityLog.warn(ActivityLog.LogCategory.EXECUTION,
                        "Audit-Finding ohne Beleg im Faktenblatt: " + item.title()
                                + " (" + report.getAuditKey() + ") — kein verwaltetes TODO",
                        "AuditIngestionService", event.getRunId(), event.getWorkflowId());
            }
            finding.setUnverifiedEvidence(unverified);
            finding.setSeverity(item.severity());
            finding.setTitle(item.title());
            finding.setDescription(item.description());
            finding.setEvidence(item.evidence());
            finding.setTags(TodoMapper.listToJson(item.tags()));
            finding.setLastSeenReportId(report.getId());
            finding.setLastSeenRunId(event.getRunId());
            finding.setLastSeenAt(observedAt);
            finding.setOccurrenceCount(finding.getOccurrenceCount() + 1);
            finding = findingRepository.save(finding);
            touched.add(finding);

            if (!occurrenceRepository.existsByFindingIdAndReportId(finding.getId(), report.getId())) {
                occurrenceRepository.save(AuditFindingOccurrence.builder()
                        .findingId(finding.getId()).reportId(report.getId()).severity(item.severity())
                        .title(item.title()).description(item.description()).evidence(item.evidence())
                        .tags(TodoMapper.listToJson(item.tags())).observedAt(observedAt).build());
            }
            // Nur offene Findings bekommen ein verwaltetes TODO: akzeptierte/verworfene Risiken und
            // Wiedervorlagen (AWAITING_VERIFICATION nach Verfall) loesen keinen Fix-Loop aus.
            // Ein unbelegtes Finding ebenfalls nicht — es bleibt sichtbar, kostet aber keine
            // Arbeitszeit mehr, bis sein Beleg stimmt (TODO a94b56a0).
            if (ManagedTodoWriter.needsTodo(item.severity())
                    && finding.getStatus() == AuditFindingStatus.OPEN
                    && !finding.isUnverifiedEvidence()) {
                managedTodoWriter.ensure(finding, report);
            }
        }

        reportCollisions(event, report, collisions);

        for (AuditFinding finding : findingRepository.findByProjectIdAndAuditKey(event.getProjectId(), report.getAuditKey())) {
            if (!seenFingerprints.contains(finding.getFingerprint())
                    && (finding.getStatus() == AuditFindingStatus.OPEN
                    || finding.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION)) {
                finding.setStatus(AuditFindingStatus.RESOLVED);
                finding.setResolvedAt(observedAt);
                touched.add(findingRepository.save(finding));
            }
        }
        managedTodoWriter.reconcile(touched);
    }

    /**
     * Zwei Befunde eines Reports mit gleichem ruleKey und gleichem normalisierten subject haben eine
     * Identitaet; nur der erste wird uebernommen. Das geschah frueher still — beim Architektur-Review
     * fehlten so drei Findings, ohne dass es jemand merkte (TODO 94fda6ef).
     */
    private void reportCollisions(WorkflowRunCompletedEvent event, AuditReport report, List<String> collisions) {
        if (collisions.isEmpty()) return;
        String message = "Audit '" + report.getAuditKey() + "': " + collisions.size()
                + " Finding(s) verworfen, gleiche Identitaet (ruleKey + subject) wie ein frueheres im selben Report: "
                + collisions;
        log.warn("{} (run {})", message, event.getRunId());
        activityLog.warn(ActivityLog.LogCategory.EXECUTION, message, "AuditIngestionService",
                event.getRunId(), event.getWorkflowId());
    }

    /**
     * Haelt die Regel-Identitaet stabil: ein gedrifteter {@code ruleKey} wird auf den Katalogeintrag
     * normalisiert, ein unbekannter bleibt stehen (kein Datenverlust) und wird einmal je Run und Key
     * im Activity-Log gemeldet — sonst faellt die Drift erst an den Wiedergaenger-Findings auf
     * (Audit-TODO 8fc9a401).
     */
    private AuditRuleKeyCatalog.Resolution resolveRuleKey(WorkflowRunCompletedEvent event, AuditReport report,
                                                          ParsedFinding item, Set<String> reportedRuleKeys) {
        AuditRuleKeyCatalog.Resolution resolution = ruleKeyCatalog.resolve(report.getAuditKey(), item.ruleKey());
        if (resolution.unknown() && reportedRuleKeys.add(item.ruleKey())) {
            String message = "Audit '" + report.getAuditKey() + "': unbekannter ruleKey '" + item.ruleKey()
                    + "' — erlaubt sind " + ruleKeyCatalog.allowedKeys(report.getAuditKey())
                    + ". Das Finding wurde uebernommen, bekommt aber eine eigene Identitaet.";
            log.warn("{} (run {})", message, event.getRunId());
            activityLog.warn(ActivityLog.LogCategory.EXECUTION, message, "AuditIngestionService", event.getRunId());
        }
        return resolution;
    }

    /**
     * Identitaet eines Findings: Projekt, Audit, Regel und das <em>normalisierte</em> subject
     * ({@link AuditSubjectNormalizer}) — modellgenerierte Suffix-Drift im subject erzeugt damit
     * keinen neuen Fingerprint mehr (Audit-TODO 0e19648d). Ein {@code ¦} in Regel oder subject zaehlt
     * wie {@code |}: So zeigt {@code audit_findings} ein {@code |} in einem Feld, und ein Modell, das den
     * Befund von dort weiterfuehrt, gibt es so wieder (TODO 90c4f7db).
     */
    static String fingerprint(String projectId, String auditKey, String ruleKey, String subject) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", projectId, auditKey, AuditFindingsContext.unmask(ruleKey),
                                    AuditSubjectNormalizer.normalize(AuditFindingsContext.unmask(subject)))
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
