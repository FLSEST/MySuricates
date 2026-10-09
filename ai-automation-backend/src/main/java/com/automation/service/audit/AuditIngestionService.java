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
  * Receives the report of an audit run: validates it and stores it as {@link AuditReport}
  * (VALID/INVALID). The findings of a valid report are taken over by the {@link AuditSnapshotApplier} —
  * in the same transaction as storing the report.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AuditIngestionService {
    /** Rejection reason of an invalid envelope that names none of its own. */
    static final String INVALID_SNAPSHOT = "Invalid audit snapshot";

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
        // Provider and model are named by the AI step; a shell reportBlock has none (TODO fa66ea31).
        StepResult promptStep = step(run, config.promptBlock());
        String output = reportStep != null ? reportStep.getOutput() : "";
        ParseResult parsed = parser.parse(output);
        Optional<String> rejection = rejection(run, config, reportStep, parsed);

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
                .ingestionStatus(rejection.isEmpty() ? AuditIngestionStatus.VALID : AuditIngestionStatus.INVALID)
                .ingestionError(rejection.orElse(null))
                .generatedAt(run.getCompletedAt() != null ? run.getCompletedAt() : Instant.now())
                .build());

        if (rejection.isPresent()) {
            log.warn("Stored invalid audit report for run {}: {}", event.getRunId(), rejection.get());
            // Make it visible, not just in the log: an invalid report skips the entire
            // snapshot — no finding is created, none set to RESOLVED. Whoever expected the
            // audit should see why nothing happened (TODO e39379a9).
            activityLog.warn(ActivityLog.LogCategory.EXECUTION,
                    "Audit-Report verworfen (" + config.key() + "): " + rejection.get()
                            + " — keine Findings uebernommen, keine aufgeloest",
                    "AuditIngestionService", event.getRunId(), event.getWorkflowId());
            return;
        }

        // The measured values of this run — baseline for the evidence check (TODO a94b56a0).
        // Read here, where the steps are loaded anyway; the read side must not fetch step blobs.
        Set<String> factNames = EvidenceVerification.factNames(
                run.getSteps().stream().map(StepResult::getOutput).toList());

        if (config.invalidTodoMinSeverity() != null) {
            activityLog.warn(ActivityLog.LogCategory.EXECUTION,
                    "Audit-Konfiguration (" + config.key() + "): todoMinSeverity '" + config.invalidTodoMinSeverity()
                            + "' ist unbekannt – es gilt " + config.todoThreshold()
                            + " (erlaubt: CRITICAL, HIGH, MEDIUM, LOW, NONE)",
                    "AuditIngestionService", event.getRunId(), event.getWorkflowId());
        }
        snapshotApplier.apply(event, report, parsed.findings(), factNames, config.todoThreshold());
    }

    /**
     * Why the report is rejected, or empty when it is valid: the first failing check names the reason. The
     * stored {@code ingestionError}, the server log and the activity log all use this text, so an invalid
     * envelope without a reason of its own (the parser passes on {@code ex.getMessage()}, which may be null)
     * gets {@link #INVALID_SNAPSHOT} here, in one place.
     */
    private Optional<String> rejection(WorkflowRun run, AuditConfig config, StepResult reportStep, ParseResult parsed) {
        if (run.getStatus() != RunStatus.COMPLETED && run.getStatus() != RunStatus.COMPLETED_WITH_ERRORS) {
            return Optional.of("Run ended with status " + run.getStatus());
        }
        if (reportStep == null) {
            return Optional.of("Configured report block '" + config.reportBlock() + "' was not persisted");
        }
        if (reportStep.getStatus() != StepStatus.COMPLETED) {
            return Optional.of("Report block did not complete");
        }
        if (!parsed.valid()) {
            return Optional.of(firstNonBlank(parsed.error(), INVALID_SNAPSHOT));
        }
        return Optional.ofNullable(unknownRuleKeyError(config.key(), parsed.findings()));
    }

    /**
     * A ruleKey outside the catalog makes the <b>whole</b> report invalid — provided there is a catalog
     * for this audit at all (TODO f57945e2).
     *
     * <p>Previously an unknown key was only reported as WARN and the finding was taken
     * over anyway. For a snapshot audit that is the most expensive way to be lenient: an
     * envelope whose keys have all drifted uniformly carries the right <i>number</i> of findings
     * and thus passes the counting gate block — but the ingestion creates nothing but new identities
     * and closes the entire previous generation as RESOLVED. Result: just as many
     * revenants, and the work on the real findings starts over.
     *
     * <p>Audits without a catalog entry stay untouched — their prompts assign the keys freely.
     *
     * @return the error message, or {@code null} if all keys are known
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

    /** Delegate to {@link AuditSnapshotApplier#fingerprint} — that is where the identity of a finding is created. */
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
