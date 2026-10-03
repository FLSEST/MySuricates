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
 * setzt auf RESOLVED, was der Snapshot nicht mehr enthaelt — eingeordnete Befunde nach den Regeln von
 * {@link AuditDispositionLifecycle}.
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
    private final AuditDispositionLifecycle dispositionLifecycle;

    /**
     * Bestand und Vorkommen dieses Reports werden einmal geladen, Findings und Vorkommen gesammelt
     * gespeichert (3deaee34) — vorher je Finding eine Fingerprint-Suche, ein exists und zwei save.
     * Die Regeln je Finding sind dieselben geblieben.
     */
    void apply(WorkflowRunCompletedEvent event, AuditReport report,
               List<ParsedFinding> parsedFindings, Set<String> factNames) {
        Instant observedAt = report.getGeneratedAt();
        List<AuditFinding> known = findingRepository.findByProjectIdAndAuditKey(event.getProjectId(), report.getAuditKey());
        Map<String, AuditFinding> knownByFingerprint = new HashMap<>();
        known.forEach(finding -> knownByFingerprint.putIfAbsent(finding.getFingerprint(), finding));
        Set<String> seenFingerprints = new HashSet<>();
        Set<String> reportedRuleKeys = new HashSet<>();
        List<AuditFinding> applied = new ArrayList<>();
        List<AuditFinding> created = new ArrayList<>();
        List<ParsedFinding> appliedItems = new ArrayList<>();
        List<String> collisions = new ArrayList<>();

        for (ParsedFinding reported : parsedFindings) {
            ParsedFinding raw = unmasked(reported);
            AuditRuleKeyCatalog.Resolution resolution = resolveRuleKey(event, report, raw, reportedRuleKeys);
            String ruleKey = resolution.ruleKey();
            ParsedFinding item = withFixedSubject(report, ruleKey, raw);
            String fingerprint = fingerprint(event.getProjectId(), report.getAuditKey(), ruleKey, item.subject());
            if (!seenFingerprints.add(fingerprint)) {
                collisions.add(raw.subject());
                continue;
            }
            AuditFinding finding = knownByFingerprint.get(fingerprint);
            if (finding == null) {
                finding = AuditFinding.builder()
                        .projectId(event.getProjectId()).auditKey(report.getAuditKey()).fingerprint(fingerprint)
                        .ruleKey(ruleKey).subject(item.subject()).status(AuditFindingStatus.OPEN)
                        .firstSeenReportId(report.getId()).firstSeenRunId(event.getRunId()).firstSeenAt(observedAt)
                        .occurrenceCount(0).build();
                created.add(finding);
            } else {
                reopen(finding, item, observedAt);
            }
            update(finding, item, resolution, event, report, factNames, observedAt);
            applied.add(finding);
            appliedItems.add(item);
        }

        List<AuditFinding> absent = known.stream()
                .filter(finding -> !seenFingerprints.contains(finding.getFingerprint())).toList();
        // Vor dem Speichern und vor ensureAll: ein Nachfolger, der eine Einordnung erbt, bekommt kein TODO.
        List<AuditFinding> superseded = dispositionLifecycle.inheritOnRuleChange(created, absent, observedAt);

        List<AuditFinding> saved = applied.isEmpty() ? List.of() : findingRepository.saveAll(applied);
        List<AuditFinding> touched = new ArrayList<>(saved);
        recordOccurrences(saved, appliedItems, report, observedAt);
        // Nur offene Findings bekommen ein verwaltetes TODO: akzeptierte/verworfene Risiken und
        // Wiedervorlagen (AWAITING_VERIFICATION nach Verfall) loesen keinen Fix-Loop aus.
        // Ein unbelegtes Finding ebenfalls nicht — es bleibt sichtbar, kostet aber keine
        // Arbeitszeit mehr, bis sein Beleg stimmt (TODO a94b56a0).
        managedTodoWriter.ensureAll(saved.stream()
                .filter(finding -> ManagedTodoWriter.needsTodo(finding.getSeverity())
                        && finding.getStatus() == AuditFindingStatus.OPEN
                        && !finding.isUnverifiedEvidence())
                .toList(), report);

        reportCollisions(event, report, collisions);

        List<AuditFinding> resolved = new ArrayList<>(superseded);
        for (AuditFinding finding : absent) {
            if (finding.getStatus() == AuditFindingStatus.OPEN
                    || finding.getStatus() == AuditFindingStatus.AWAITING_VERIFICATION) {
                finding.setStatus(AuditFindingStatus.RESOLVED);
                finding.setResolvedAt(observedAt);
                resolved.add(finding);
            }
        }
        // Eingeordnete schliesst die Abwesenheit nicht sofort: nur bei ausgemusterter Regel oder nach
        // mehreren Laeufen ohne Wiedersehen (b2e3da6a, 276ac76f).
        resolved.addAll(dispositionLifecycle.retireAbsent(absent, report, observedAt));
        if (!resolved.isEmpty()) touched.addAll(findingRepository.saveAll(resolved));
        managedTodoWriter.reconcile(touched);
    }

    /** Ein bekanntes Finding taucht wieder auf: zurueck ins Offene, Akzeptanz neu bewerten. */
    private void reopen(AuditFinding finding, ParsedFinding item, Instant observedAt) {
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

    /** Die Felder, die jeder Ingest neu setzt. */
    private void update(AuditFinding finding, ParsedFinding item, AuditRuleKeyCatalog.Resolution resolution,
                        WorkflowRunCompletedEvent event, AuditReport report, Set<String> factNames,
                        Instant observedAt) {
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
    }

    /** Ein Vorkommen je Finding und Report; was der Report schon hat, bleibt (eine Abfrage, ein saveAll). */
    private void recordOccurrences(List<AuditFinding> saved, List<ParsedFinding> items, AuditReport report,
                                   Instant observedAt) {
        if (saved.isEmpty()) return;
        Set<String> recorded = new HashSet<>(occurrenceRepository.findFindingIdsByReportId(report.getId()));
        List<AuditFindingOccurrence> occurrences = new ArrayList<>();
        for (int i = 0; i < saved.size(); i++) {
            AuditFinding finding = saved.get(i);
            ParsedFinding item = items.get(i);
            if (recorded.add(finding.getId())) {
                occurrences.add(AuditFindingOccurrence.builder()
                        .findingId(finding.getId()).reportId(report.getId()).severity(item.severity())
                        .title(item.title()).description(item.description()).evidence(item.evidence())
                        .tags(TodoMapper.listToJson(item.tags())).observedAt(observedAt).build());
            }
        }
        if (!occurrences.isEmpty()) occurrenceRepository.saveAll(occurrences);
    }

    /**
     * {@code audit_findings} zeigt ein {@code |} in ruleKey, subject und Titel als {@code ¦}, und ein Modell fuehrt
     * den Befund genau so weiter. Gespeichert – und im Audit Center gezeigt – wird wieder das {@code |}
     * (TODO 90c4f7db); die Identitaet ist ohnehin dieselbe ({@link #fingerprint}).
     */
    private static ParsedFinding unmasked(ParsedFinding item) {
        return new ParsedFinding(AuditFindingsContext.unmask(item.ruleKey()), AuditFindingsContext.unmask(item.subject()),
                item.severity(), AuditFindingsContext.unmask(item.title()), item.description(), item.evidence(),
                item.tags());
    }

    /**
     * Regeln, die das Repository oder ein Modul als Ganzes betreffen, haben im Katalog ein festes subject;
     * das gilt, egal was das Modell schickt (TODO 802f740a). Vorher stand es nur als Bitte im Prompt, und
     * ein abweichendes subject kam als Wiedergaenger ohne die Disposition des alten Befunds zurueck. Der
     * feste Wert ist der, den der Prompt verlangt — ein Befund, der sich daran hielt, behaelt seine Identitaet.
     */
    private ParsedFinding withFixedSubject(AuditReport report, String ruleKey, ParsedFinding item) {
        String fixed = ruleKeyCatalog.fixedSubject(report.getAuditKey(), ruleKey);
        if (fixed == null || fixed.equals(item.subject())) {
            return item;
        }
        log.info("Audit '{}': subject '{}' of rule {} replaced by its fixed value '{}'",
                report.getAuditKey(), item.subject(), ruleKey, fixed);
        return new ParsedFinding(item.ruleKey(), fixed, item.severity(), item.title(), item.description(),
                item.evidence(), item.tags());
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
