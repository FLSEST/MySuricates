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
  * Takes over the findings of a valid audit report into the stock: matches each finding by
  * fingerprint, reopens or re-evaluates known ones, maintains occurrences and managed TODOs, and
  * sets to RESOLVED whatever the snapshot no longer contains — triaged findings according to the rules of
  * {@link AuditDispositionLifecycle}.
 *
  * <p>Moved out of {@code AuditIngestionService} (audit TODO aac5fbf7): the service validates and stores
  * the report, here it is applied — within the transaction of
  * {@link AuditIngestionService#ingest}, there are deliberately no transaction boundaries of their own here.
  * Body of {@code ingestSnapshot} (here {@link #apply}) and activity log texts unchanged, including
  * source {@code "AuditIngestionService"}.
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
     * Stock and occurrences of this report are loaded once, findings and occurrences are saved
     * collectively (3deaee34) — previously per finding a fingerprint lookup, an exists and two saves.
     * The rules per finding have stayed the same.
     */
    void apply(WorkflowRunCompletedEvent event, AuditReport report,
               List<ParsedFinding> parsedFindings, Set<String> factNames, AuditTodoThreshold todoThreshold) {
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
        // Before saving and before ensureAll: a successor that inherits a triage gets no TODO.
        List<AuditFinding> superseded = dispositionLifecycle.inheritOnRuleChange(created, absent, observedAt);

        List<AuditFinding> saved = applied.isEmpty() ? List.of() : findingRepository.saveAll(applied);
        List<AuditFinding> touched = new ArrayList<>(saved);
        recordOccurrences(saved, appliedItems, report, observedAt);
        // Only open findings at or above the workflow's threshold get a managed TODO (d8607f77): accepted/dismissed
        // risks and resubmissions (AWAITING_VERIFICATION after expiry) trigger no fix loop.
        // An unsubstantiated finding neither — it stays visible but no longer costs
        // working time until its evidence holds (TODO a94b56a0).
        managedTodoWriter.ensureAll(saved.stream()
                .filter(finding -> todoThreshold.admits(finding.getSeverity())
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
        // Absence does not close triaged ones immediately: only for a retired rule or after
        // several runs without a re-sighting (b2e3da6a, 276ac76f).
        resolved.addAll(dispositionLifecycle.retireAbsent(absent, report, factNames, observedAt));
        if (!resolved.isEmpty()) touched.addAll(findingRepository.saveAll(resolved));
        managedTodoWriter.reconcile(touched, todoThreshold);
    }

    /** A known finding reappears: back to open, re-evaluate acceptance. */
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

    /** The fields that every ingest sets anew. */
    private void update(AuditFinding finding, ParsedFinding item, AuditRuleKeyCatalog.Resolution resolution,
                        WorkflowRunCompletedEvent event, AuditReport report, Set<String> factNames,
                        Instant observedAt) {
        // subject is a pure display attribute — the newest value wins, the fingerprint hangs
        // on its normalized form and thereby stays stable across drift.
        finding.setSubject(item.subject());
        // Set anew on every ingest, in both directions: if the catalog adds the key,
        // the marker drops by itself on the next run (TODO ffa6d846).
        finding.setUnknownRuleKey(resolution.unknown());
        // Likewise anew on every ingest: does the evidence name a measured value of this run? (a94b56a0)
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

    /** One occurrence per finding and report; what the report already has stays (one query, one saveAll). */
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
     * {@code audit_findings} shows a {@code |} in ruleKey, subject, title and evidence as {@code ¦}, and a model
     * carries the finding on exactly like that. What is stored – and shown in the Audit Center – is the {@code |} again
     * (TODO 90c4f7db); the identity is the same anyway ({@link #fingerprint}).
     */
    private static ParsedFinding unmasked(ParsedFinding item) {
        return new ParsedFinding(AuditFindingsContext.unmask(item.ruleKey()), AuditFindingsContext.unmask(item.subject()),
                item.severity(), AuditFindingsContext.unmask(item.title()), item.description(),
                AuditFindingsContext.unmask(item.evidence()), item.tags());
    }

    /**
     * Rules that concern the repository or a module as a whole have a fixed subject in the catalog;
     * it applies no matter what the model sends (TODO 802f740a). Previously it was only a request in the prompt, and
     * a deviating subject came back as a revenant without the disposition of the old finding. The
     * fixed value is the one the prompt demands — a finding that adhered to it keeps its identity.
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
     * Two findings of a report with the same ruleKey and the same normalized subject have one
     * identity; only the first is taken over. This used to happen silently — in the architecture review
     * three findings were missing this way without anyone noticing (TODO 94fda6ef).
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
     * Keeps the rule identity stable: a drifted {@code ruleKey} is normalized to the catalog entry,
     * an unknown one stays (no data loss) and is reported once per run and key
     * in the activity log — otherwise the drift is only noticed on the revenant findings
     * (audit TODO 8fc9a401).
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
     * Identity of a finding: project, audit, rule and the <em>normalized</em> subject
     * ({@link AuditSubjectNormalizer}) — model-generated suffix drift in the subject thus no longer creates
     * a new fingerprint (audit TODO 0e19648d). A {@code ¦} in rule or subject counts
     * like {@code |}: this is how {@code audit_findings} shows a {@code |} in a field, and a model that carries the
     * finding on from there returns it that way (TODO 90c4f7db).
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
