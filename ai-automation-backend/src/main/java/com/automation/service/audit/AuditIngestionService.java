package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.*;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditResultParser.ParseResult;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.*;

/**
 * Nimmt den Report eines Audit-Laufs entgegen: prueft ihn und speichert ihn als {@link AuditReport}
 * (VALID/INVALID). Die Findings eines gueltigen Reports uebernimmt der {@link AuditSnapshotApplier} —
 * in derselben Transaktion wie das Speichern des Reports.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditIngestionService {
    private final WorkflowRunRepository runRepository;
    private final AuditReportRepository reportRepository;
    private final AuditResultParser parser;
    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final ActivityLogService activityLog;
    private final AuditSnapshotApplier snapshotApplier;

    @Transactional
    public void ingest(WorkflowRunCompletedEvent event) {
        if (reportRepository.existsByWorkflowRunId(event.getRunId())) return;

        AuditConfig config = AuditConfig.from(event.getWorkflowMetadata());
        WorkflowRun run = runRepository.findByIdWithSteps(event.getRunId()).orElse(null);
        if (run == null || config == null) {
            log.warn("Cannot ingest audit run {}: run or audit config missing", event.getRunId());
            return;
        }

        StepResult reportStep = step(run, config.reportBlock());
        // Provider und Modell nennt der KI-Schritt; ein Shell-reportBlock hat keine (TODO fa66ea31).
        StepResult promptStep = step(run, config.promptBlock());
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
                .provider(firstNonBlank(metadataValue(promptStep, "provider"), metadataValue(reportStep, "provider"),
                        run.getWorkflow().getProvider()))
                .model(firstNonBlank(metadataValue(promptStep, "model"), metadataValue(reportStep, "model"),
                        run.getWorkflow().getModel()))
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

        snapshotApplier.apply(event, report, parsed.findings(), factNames);
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

    /** Delegat auf {@link AuditSnapshotApplier#fingerprint} — dort entsteht die Identitaet eines Findings. */
    static String fingerprint(String projectId, String auditKey, String ruleKey, String subject) {
        return AuditSnapshotApplier.fingerprint(projectId, auditKey, ruleKey, subject);
    }

    private static GateStatus parseGate(Object value) {
        try { return value != null ? GateStatus.valueOf(String.valueOf(value)) : null; }
        catch (IllegalArgumentException ex) { return null; }
    }
    private static StepResult step(WorkflowRun run, String blockName) {
        return run.getSteps().stream()
                .filter(step -> blockName.equals(String.valueOf(step.getMetadata().get("blockName"))))
                .findFirst().orElse(null);
    }
    private static String metadataValue(StepResult step, String key) {
        return step != null ? asString(step.getMetadata().get(key)) : null;
    }
    private static String asString(Object value) { return value != null ? String.valueOf(value) : null; }
    private static String firstNonBlank(String... values) {
        return Arrays.stream(values).filter(v -> v != null && !v.isBlank()).findFirst().orElse(null);
    }

}
