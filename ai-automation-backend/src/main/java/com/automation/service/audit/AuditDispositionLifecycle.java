package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.repository.AuditReportRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
  * What becomes of triaged findings ({@code ACCEPTED_RISK}, {@code DISMISSED}) that a valid,
  * complete snapshot no longer reports (TODOs b2e3da6a, 276ac76f). Open findings fall to {@code RESOLVED}
  * immediately in that case; triaged ones used to stay forever — in the accepted counters
  * and without the 180-day resubmission, because {@link AuditAcceptancePolicy} only evaluates on a re-sighting.
 *
 * <ul>
  *   <li><b>Rule change per file:</b> If the same run reports a new finding for the same (normalized) file subject
  *       under a different ruleKey, the new one inherits the triage including the note, the old one
  *       becomes {@code RESOLVED} („Ersetzt durch …“). Each predecessor passes its triage on at most once; a
  *       successor with higher severity (CRITICAL/HIGH) inherits nothing and is evaluated anew. Only for subjects that name a file or class —
  *       {@code repository} or a FAKT name mean something different for every rule.</li>
  *   <li><b>Rule retired:</b> If the ruleKey is no longer in the audit's catalog, no
  *       valid report can report it any more — {@code RESOLVED} with {@value #NOTE_RULE_RETIRED}.</li>
  *   <li><b>No longer seen:</b> If the finding is missing in {@value #ABSENT_RUNS} valid runs in a row
  *       (this one included), it becomes {@code RESOLVED} with {@link #NOTE_NOT_SEEN} — for a metric rule
  *       only in a run that measured its FAKT. The prompts carry
  *       triaged findings on in the envelope as long as they exist; a single run that
  *       forgets one therefore does not close it yet.</li>
 * </ul>
  * Pure DB work in the transaction of {@link AuditIngestionService#ingest}; saving and closing
  * managed TODOs stay with the {@link AuditSnapshotApplier}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AuditDispositionLifecycle {

    /** This many valid runs in a row without a re-sighting, then a triaged finding counts as done. */
    static final int ABSENT_RUNS = 3;
    static final String NOTE_RULE_RETIRED = "Regel nicht mehr im Katalog";
    static final String NOTE_NOT_SEEN = "Nicht mehr gemessen/ersetzt: " + ABSENT_RUNS + " Läufe ohne Wiedersehen";
    static final String NOTE_INHERITED = "Übernommen von ";
    static final String NOTE_SUPERSEDED = "Ersetzt durch ";
    private static final String RULE_CHANGE = " (Regelwechsel)";

    /**
     * File subject: a path (with {@code /} or an extension) or a CamelCase class name with at least two
     * capital letters ({@code WorkflowExecutor}). A single word ({@code Scheduler}), a route with
     * spaces or an {@code <art>:<wert>} mean something different for every rule.
     */
    private static final Pattern PATH = Pattern.compile("[^\\s:]*/[^\\s:]*|[^\\s:/]+\\.[A-Za-z0-9]+");
    /** Form of a FAKT name: lowercase, with underscore ({@code database_bytes}, {@code frontend_tsc_errors}). */
    private static final Pattern FACT_NAME = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)+");
    private static final Pattern CAMEL_CASE_CLASS = Pattern.compile("[A-Z][a-z0-9_]+[A-Z][A-Za-z0-9_]*");

    private static final Comparator<AuditFinding> LATEST_DISPOSITION_FIRST = Comparator
            .comparing(AuditDispositionLifecycle::dispositionTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AuditFinding::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final AuditReportRepository reportRepository;

    /**
     * New findings of this run inherit the triage of a predecessor that is no longer reported and has the same
     * file subject and a different ruleKey.
     *
     * @param created  findings newly created in this run (fields already set, not yet saved)
     * @param absent   known findings that this run did not report
     * @return the superseded predecessors, now {@code RESOLVED}
     */
    List<AuditFinding> inheritOnRuleChange(List<AuditFinding> created, List<AuditFinding> absent, Instant observedAt) {
        List<AuditFinding> predecessors = new ArrayList<>(absent.stream()
                .filter(finding -> isDisposed(finding.getStatus()) && fileSubject(finding) != null)
                .sorted(LATEST_DISPOSITION_FIRST)
                .toList());
        if (predecessors.isEmpty() || created.isEmpty()) {
            return List.of();
        }
        // predecessor → ruleKeys of its successors (AuditFinding compares by identity)
        Map<AuditFinding, Set<String>> superseded = new LinkedHashMap<>();
        for (AuditFinding successor : created) {
            String subject = fileSubject(successor);
            if (subject == null) continue;
            // 1:1 — a predecessor passes its triage on at most once, after that it is out of the pool.
            predecessors.stream()
                    .filter(predecessor -> subject.equals(fileSubject(predecessor))
                            && !predecessor.getRuleKey().equals(successor.getRuleKey())
                            && !escalates(predecessor, successor))
                    .findFirst()
                    .ifPresent(predecessor -> {
                        inherit(successor, predecessor);
                        predecessors.remove(predecessor);
                        superseded.computeIfAbsent(predecessor, key -> new LinkedHashSet<>()).add(successor.getRuleKey());
                    });
        }
        superseded.forEach((predecessor, successorRules) ->
                resolve(predecessor, NOTE_SUPERSEDED + String.join(", ", successorRules) + RULE_CHANGE, observedAt));
        return List.copyOf(superseded.keySet());
    }

    /**
     * Closes triaged findings that this valid snapshot did not report: retired rule
     * immediately, otherwise after {@value #ABSENT_RUNS} runs without a re-sighting.
     *
     * @param absent    known findings that this run did not report (non-triaged ones are skipped)
     * @param factNames the FAKT names of this run: a metric rule whose FAKT is missing was not measurable —
     *                  its finding stays (partial measurement)
     * @return the findings now set to {@code RESOLVED}
     */
    List<AuditFinding> retireAbsent(List<AuditFinding> absent, AuditReport report, Set<String> factNames,
                                    Instant observedAt) {
        List<AuditFinding> retired = new ArrayList<>();
        List<AuditFinding> stillCatalogued = new ArrayList<>();
        for (AuditFinding finding : absent) {
            if (!isDisposed(finding.getStatus())) continue;
            if (ruleKeyCatalog.resolve(finding.getAuditKey(), finding.getRuleKey()).unknown()) {
                resolve(finding, NOTE_RULE_RETIRED, observedAt);
                retired.add(finding);
            } else {
                stillCatalogued.add(finding);
            }
        }
        if (stillCatalogued.isEmpty()) {
            return retired;
        }
        Instant cutoff = absenceCutoff(report);
        if (cutoff == null) {
            return retired;
        }
        for (AuditFinding finding : stillCatalogued) {
            if (isUnmeasuredMetric(finding, factNames)) {
                continue;
            }
            if (finding.getLastSeenAt() != null && finding.getLastSeenAt().isBefore(cutoff)) {
                resolve(finding, NOTE_NOT_SEEN, observedAt);
                retired.add(finding);
            }
        }
        return retired;
    }

    /**
     * Whoever was last seen before this point in time is missing from the current run and the {@value #ABSENT_RUNS} − 1
     * valid runs before it: the point in time of the oldest of those earlier runs. {@code null} as long as there are not that many
     * earlier runs yet.
     */
    private Instant absenceCutoff(AuditReport report) {
        if (report.getGeneratedAt() == null) {
            return null;
        }
        List<Instant> previous = reportRepository.findValidGeneratedAtBefore(report.getProjectId(),
                report.getAuditKey(), report.getGeneratedAt(), PageRequest.of(0, ABSENT_RUNS - 1));
        return previous.size() < ABSENT_RUNS - 1 ? null : previous.get(ABSENT_RUNS - 2);
    }

    /**
     * A successor with high severity (CRITICAL/HIGH) that is above the predecessor's inherits nothing: the
     * triage applied to a lighter finding, the new one has to be evaluated anew.
     */
    private static boolean escalates(AuditFinding predecessor, AuditFinding successor) {
        AuditSeverity after = successor.getSeverity();
        AuditSeverity before = predecessor.getSeverity();
        return after != null && before != null && after.compareTo(AuditSeverity.HIGH) <= 0 && after.compareTo(before) < 0;
    }

    private static void inherit(AuditFinding successor, AuditFinding predecessor) {
        successor.setStatus(predecessor.getStatus());
        successor.setResolvedAt(predecessor.getResolvedAt());
        successor.setDispositionAt(dispositionTime(predecessor));
        // Baseline of the worsening rule: the evidence of the new rule, not that of the old one —
        // its numbers measure something different. The acceptance deadline keeps running.
        successor.setDispositionEvidence(predecessor.getStatus() == AuditFindingStatus.ACCEPTED_RISK
                ? successor.getEvidence() : predecessor.getDispositionEvidence());
        successor.setDispositionNote(appendNote(predecessor.getDispositionNote(),
                NOTE_INHERITED + predecessor.getRuleKey() + RULE_CHANGE));
        log.info("New audit finding {} / {} inherits the {} of {} ({}) — rule change",
                successor.getRuleKey(), successor.getSubject(), predecessor.getStatus(),
                predecessor.getId(), predecessor.getRuleKey());
    }

    private static void resolve(AuditFinding finding, String note, Instant observedAt) {
        log.info("Audit finding {} ({} / {}, {}) resolved: {}", finding.getId(), finding.getRuleKey(),
                finding.getSubject(), finding.getStatus(), note);
        finding.setStatus(AuditFindingStatus.RESOLVED);
        finding.setResolvedAt(observedAt);
        finding.setDispositionNote(appendNote(finding.getDispositionNote(), note));
    }

    /**
     * Metric rule (fixed subject in FAKT form, {@code database_bytes}) whose FAKT is missing from this run's fact sheet:
     * The run did not measure it, its silence is not an absence. Fixed subjects like
     * {@code repository} or {@code LICENSE} are not FAKT names and stay untouched.
     */
    private boolean isUnmeasuredMetric(AuditFinding finding, Set<String> factNames) {
        String fixed = ruleKeyCatalog.fixedSubject(finding.getAuditKey(), finding.getRuleKey());
        return fixed != null && FACT_NAME.matcher(fixed).matches() && !factNames.contains(fixed);
    }

    /** The normalized subject, if it names a file or class and the rule has no fixed value. */
    private String fileSubject(AuditFinding finding) {
        if (ruleKeyCatalog.fixedSubject(finding.getAuditKey(), finding.getRuleKey()) != null) {
            return null;
        }
        String subject = subjectOf(finding);
        if (subject.indexOf(':') >= 0) {
            return null; // <art>:<wert>
        }
        // Path or extension count in the reported form: the normalizer turns Foo.java into the class name Foo.
        String raw = AuditFindingsContext.unmask(finding.getSubject()).trim();
        int colon = raw.indexOf(':');
        String reported = colon >= 0 ? raw.substring(0, colon).trim() : raw;
        return PATH.matcher(reported).matches() || CAMEL_CASE_CLASS.matcher(subject).matches() ? subject : null;
    }

    private static String subjectOf(AuditFinding finding) {
        return AuditSubjectNormalizer.normalize(AuditFindingsContext.unmask(finding.getSubject()));
    }

    private static Instant dispositionTime(AuditFinding finding) {
        return finding.getDispositionAt() != null ? finding.getDispositionAt() : finding.getResolvedAt();
    }

    private static boolean isDisposed(AuditFindingStatus status) {
        return status == AuditFindingStatus.ACCEPTED_RISK || status == AuditFindingStatus.DISMISSED;
    }

    private static String appendNote(String existing, String hint) {
        return existing == null || existing.isBlank() ? hint : existing.stripTrailing() + "\n" + hint;
    }
}
