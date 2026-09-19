package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.mapper.TodoMapper;
import com.automation.repository.*;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditResultParser.ParseResult;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuditIngestionService {
    private final WorkflowRunRepository runRepository;
    private final AuditReportRepository reportRepository;
    private final AuditFindingRepository findingRepository;
    private final AuditFindingOccurrenceRepository occurrenceRepository;
    private final AuditFindingTodoRepository findingTodoRepository;
    private final TodoRepository todoRepository;
    private final AuditResultParser parser;
    private final AuditAcceptancePolicy acceptancePolicy;
    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final ActivityLogService activityLog;
    private final ManagedTodoWriter managedTodoWriter;

    @Transactional
    public void ingest(WorkflowRunCompletedEvent event) {
        if (reportRepository.existsByWorkflowRunId(event.getRunId())) return;

        AuditConfig config = AuditConfig.from(event.getWorkflowMetadata());
        WorkflowRun run = runRepository.findByIdWithSteps(event.getRunId()).orElse(null);
        if (run == null || config == null) {
            log.warn("Cannot ingest audit run {}: run or audit config missing", event.getRunId());
            return;
        }

        StepResult reportStep = run.getSteps().stream()
                .filter(step -> config.reportBlock().equals(String.valueOf(step.getMetadata().get("blockName"))))
                .findFirst().orElse(null);
        String output = reportStep != null ? reportStep.getOutput() : "";
        ParseResult parsed = parser.parse(output);
        boolean successful = run.getStatus() == RunStatus.COMPLETED || run.getStatus() == RunStatus.COMPLETED_WITH_ERRORS;
        String ruleKeyError = parsed.valid() ? unknownRuleKeyError(config.key(), parsed.findings()) : null;
        String error = !successful ? "Run ended with status " + run.getStatus()
                : reportStep == null ? "Configured report block '" + config.reportBlock() + "' was not persisted"
                : reportStep.getStatus() != StepStatus.COMPLETED ? "Report block did not complete"
                : parsed.error() != null ? parsed.error() : ruleKeyError;
        boolean valid = successful && reportStep != null && reportStep.getStatus() == StepStatus.COMPLETED
                && parsed.valid() && ruleKeyError == null;

        AuditReport report = reportRepository.save(AuditReport.builder()
                .projectId(event.getProjectId())
                .workflowId(event.getWorkflowId())
                .workflowRunId(event.getRunId())
                .auditKey(config.key())
                .category(config.category())
                .title(event.getWorkflowName())
                .markdownContent(parsed.markdown())
                .gateStatus(parseGate(run.getMetadata().get("gate_status")))
                .gateDescription(asString(run.getMetadata().get("gate_description")))
                .provider(firstNonBlank(metadataValue(reportStep, "provider"), run.getWorkflow().getProvider()))
                .model(firstNonBlank(metadataValue(reportStep, "model"), run.getWorkflow().getModel()))
                .workflowVersion(run.getWorkflow().getVersion())
                .ingestionStatus(valid ? AuditIngestionStatus.VALID : AuditIngestionStatus.INVALID)
                .ingestionError(valid ? null : firstNonBlank(error, "Invalid audit snapshot"))
                .generatedAt(run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now())
                .build());

        if (!valid) {
            log.warn("Stored invalid audit report for run {}: {}", event.getRunId(), error);
            // Sichtbar machen, nicht nur ins Log: Ein ungueltiger Report laesst den kompletten
            // Snapshot aus — kein Finding wird angelegt, keines auf RESOLVED gesetzt. Wer den
            // Audit erwartet hat, soll sehen, warum nichts passiert ist (TODO e39379a9).
            activityLog.warn(ActivityLog.LogCategory.EXECUTION,
                    "Audit-Report verworfen (" + config.key() + "): " + firstNonBlank(error, "ungueltiger Snapshot")
                            + " — keine Findings uebernommen, keine aufgeloest",
                    "AuditIngestionService", event.getRunId(), event.getWorkflowId());
            return;
        }

        // Die Messwerte dieses Laufs — Vergleichsbasis fuer die Belegpruefung (TODO a94b56a0).
        // Hier gelesen, wo die Steps ohnehin geladen sind; die Leseseite darf keine Step-Blobs holen.
        Set<String> factNames = EvidenceVerification.factNames(
                run.getSteps().stream().map(StepResult::getOutput).toList());

        ingestSnapshot(event, report, parsed.findings(), factNames);
    }

    private void ingestSnapshot(WorkflowRunCompletedEvent event, AuditReport report,
                                List<ParsedFinding> parsedFindings, Set<String> factNames) {
        Instant observedAt = report.getGeneratedAt();
        Set<String> seenFingerprints = new HashSet<>();
        Set<String> reportedRuleKeys = new HashSet<>();
        List<AuditFinding> touched = new ArrayList<>();

        for (ParsedFinding item : parsedFindings) {
            AuditRuleKeyCatalog.Resolution resolution = resolveRuleKey(event, report, item, reportedRuleKeys);
            String ruleKey = resolution.ruleKey();
            String fingerprint = fingerprint(event.getProjectId(), report.getAuditKey(), ruleKey, item.subject());
            if (!seenFingerprints.add(fingerprint)) continue;

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
            if ((item.severity() == AuditSeverity.CRITICAL || item.severity() == AuditSeverity.HIGH)
                    && finding.getStatus() == AuditFindingStatus.OPEN
                    && !finding.isUnverifiedEvidence()) {
                managedTodoWriter.ensure(finding, report);
            }
        }

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
     * Ein ruleKey außerhalb des Katalogs macht den <b>ganzen</b> Report ungültig — sofern es für
     * dieses Audit überhaupt einen Katalog gibt (TODO f57945e2).
     *
     * <p>Zuvor wurde ein unbekannter Key nur als WARN gemeldet und das Finding trotzdem
     * übernommen. Das ist bei einem Snapshot-Audit die teuerste Art, nachsichtig zu sein: Ein
     * Envelope, dessen Keys einheitlich gedriftet sind, trägt die richtige <i>Anzahl</i> Findings
     * und passiert damit den zählenden Gate-Block — die Ingestion legt aber lauter neue Identitäten
     * an und schließt die komplette Vorgängergeneration als RESOLVED. Ergebnis: ebenso viele
     * Wiedergänger, und die Arbeit an den echten Befunden beginnt von vorn.
     *
     * <p>Audits ohne Katalogeintrag bleiben unberührt — ihre Prompts vergeben die Keys frei.
     *
     * @return die Fehlermeldung, oder {@code null}, wenn alle Keys bekannt sind
     */
    private String unknownRuleKeyError(String auditKey, List<ParsedFinding> findings) {
        if (!ruleKeyCatalog.isChecked(auditKey)) {
            return null;
        }
        Set<String> unknown = new LinkedHashSet<>();
        for (ParsedFinding item : findings) {
            if (ruleKeyCatalog.resolve(auditKey, item.ruleKey()).unknown()) {
                unknown.add(item.ruleKey());
            }
        }
        if (unknown.isEmpty()) {
            return null;
        }
        return "Unbekannte ruleKeys für Audit '" + auditKey + "': " + unknown
                + " — erlaubt sind " + ruleKeyCatalog.allowedKeys(auditKey);
    }

    /**
     * Identitaet eines Findings: Projekt, Audit, Regel und das <em>normalisierte</em> subject
     * ({@link AuditSubjectNormalizer}) — modellgenerierte Suffix-Drift im subject erzeugt damit
     * keinen neuen Fingerprint mehr (Audit-TODO 0e19648d).
     */
    static String fingerprint(String projectId, String auditKey, String ruleKey, String subject) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", projectId, auditKey, ruleKey, AuditSubjectNormalizer.normalize(subject))
                            .getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static GateStatus parseGate(Object value) {
        try { return value != null ? GateStatus.valueOf(String.valueOf(value)) : null; }
        catch (IllegalArgumentException ex) { return null; }
    }
    private static String metadataValue(StepResult step, String key) {
        return step != null ? asString(step.getMetadata().get(key)) : null;
    }
    private static String asString(Object value) { return value != null ? String.valueOf(value) : null; }
    private static String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

}
