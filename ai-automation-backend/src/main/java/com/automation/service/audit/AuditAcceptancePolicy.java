package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

/**
 * Rules for accepted audit risks (ACCEPTED_RISK).
 * <ul>
 *   <li><b>Evidence snapshot:</b> On acceptance, the current evidence text including the timestamp is frozen on the
 *       finding ({@link #accept}).</li>
 *   <li><b>Degradation:</b> If the finding reappears and a measurable value of the new
 *       evidence is more than {@value #DEGRADATION_THRESHOLD_PERCENT} % above the snapshot (per common unit
 *       or named count, see {@link EvidenceMetrics#parse}), it becomes OPEN again; the note gets
 *       a "Verschlechtert" hint, the snapshot is kept. The rate of change (churn) fluctuates and
 *       therefore does not count as a percentage: it only degrades if it was below the hotspot threshold in the snapshot
 *       and now reaches it (threshold from "Hotspot ab N" of the current evidence, otherwise
 *       {@value #HOTSPOT_MIN_CHANGES} like HOTSPOT_MIN_CHANGES in scripts/public-readiness/arch-findings.py).
 *       A vulnerability identifier (CVE, GHSA, see {@link EvidenceMetrics#advisoryIds}) that the current evidence names
 *       and the snapshot does not degrades as well ("Verschlechtert: neue Kennung CVE-…"): the security audit keeps one
 *       finding per package, and an acceptance only covers the identifiers known at the time (TODO 1f4e20f9).</li>
 *   <li><b>Expiry:</b> If the acceptance is older than {@link #REVIEW_INTERVAL}, the finding goes to
 *       AWAITING_VERIFICATION ("Wiedervorlage") the next time it appears. After that the normal
 *       ingestion rules apply; accepting again resets snapshot and deadline.</li>
 * </ul>
 * Without comparable numbers or a new identifier there is no reopening.
 */
@Component
public class AuditAcceptancePolicy {
    public static final int DEGRADATION_THRESHOLD_PERCENT = 10;
    public static final int HOTSPOT_MIN_CHANGES = 5;
    public static final Duration REVIEW_INTERVAL = Duration.ofDays(180);
    static final String REVIEW_NOTE = "Wiedervorlage nach 180 Tagen";
    static final String DEGRADED_PREFIX = "Verschlechtert: ";
    static final String NEW_ID = "neue Kennung ";
    static final String NEW_IDS = "neue Kennungen ";

    public enum Outcome { UNCHANGED, REOPENED, REVIEW_DUE }

    /** Freezes evidence and time of acceptance on the finding. */
    public void accept(AuditFinding finding, Instant acceptedAt) {
        finding.setDispositionEvidence(finding.getEvidence());
        finding.setDispositionAt(acceptedAt);
    }

    /**
     * Evaluates a reappeared finding against its snapshot. Mutates status and note,
     * is a no-op for all statuses except ACCEPTED_RISK.
     */
    public Outcome reassess(AuditFinding finding, String currentEvidence, Instant observedAt) {
        if (finding.getStatus() != AuditFindingStatus.ACCEPTED_RISK) return Outcome.UNCHANGED;

        List<String> degradations = degradations(finding.getDispositionEvidence(), currentEvidence);
        if (!degradations.isEmpty()) {
            finding.setStatus(AuditFindingStatus.OPEN);
            finding.setResolvedAt(null);
            finding.setDispositionNote(appendNote(finding.getDispositionNote(),
                    DEGRADED_PREFIX + String.join(", ", degradations)));
            return Outcome.REOPENED;
        }

        Instant acceptedAt = finding.getDispositionAt() != null ? finding.getDispositionAt() : finding.getResolvedAt();
        if (acceptedAt != null && observedAt != null && !observedAt.isBefore(acceptedAt.plus(REVIEW_INTERVAL))) {
            finding.setStatus(AuditFindingStatus.AWAITING_VERIFICATION);
            finding.setResolvedAt(null);
            finding.setDispositionNote(appendNote(finding.getDispositionNote(), REVIEW_NOTE));
            return Outcome.REVIEW_DUE;
        }
        return Outcome.UNCHANGED;
    }

    /**
     * @return "old → new" per common unit or count whose value rose by more than the threshold,
     *         plus the rate of change if it newly reached the hotspot threshold,
     *         plus the identifiers the snapshot does not name yet.
     */
    static List<String> degradations(String snapshotEvidence, String currentEvidence) {
        Map<String, Double> before = EvidenceMetrics.parse(snapshotEvidence);
        Map<String, Double> after = EvidenceMetrics.parse(currentEvidence);
        List<String> result = new ArrayList<>();
        for (Map.Entry<String, Double> entry : before.entrySet()) {
            Double current = after.get(entry.getKey());
            if (current == null) continue;
            double old = entry.getValue();
            boolean degraded = old == 0 ? current > 0 : (current - old) / old * 100 > DEGRADATION_THRESHOLD_PERCENT;
            if (degraded) {
                result.add(EvidenceMetrics.format(old, entry.getKey()) + " → " + EvidenceMetrics.format(current, entry.getKey()));
            }
        }
        churnDegradation(snapshotEvidence, currentEvidence).ifPresent(result::add);
        newAdvisoryIds(snapshotEvidence, currentEvidence).ifPresent(result::add);
        return result;
    }

    /**
     * Identifiers that only the current evidence names: nobody assessed them when accepting. Order and spelling do not
     * matter, vanished identifiers do not reopen. Without a snapshot (acceptance from before V69) there is nothing to
     * compare against — as with the numbers.
     */
    private static Optional<String> newAdvisoryIds(String snapshotEvidence, String currentEvidence) {
        if (snapshotEvidence == null || snapshotEvidence.isBlank()) return Optional.empty();
        Set<String> added = EvidenceMetrics.advisoryIds(currentEvidence);
        added.removeAll(EvidenceMetrics.advisoryIds(snapshotEvidence));
        if (added.isEmpty()) return Optional.empty();
        return Optional.of((added.size() == 1 ? NEW_ID : NEW_IDS) + String.join(", ", added));
    }

    private static Optional<String> churnDegradation(String snapshotEvidence, String currentEvidence) {
        OptionalInt before = EvidenceMetrics.churn(snapshotEvidence);
        OptionalInt after = EvidenceMetrics.churn(currentEvidence);
        if (before.isEmpty() || after.isEmpty()) return Optional.empty();
        int threshold = EvidenceMetrics.hotspotThreshold(currentEvidence).orElse(HOTSPOT_MIN_CHANGES);
        if (before.getAsInt() >= threshold || after.getAsInt() < threshold) return Optional.empty();
        return Optional.of(changes(before.getAsInt()) + " → " + changes(after.getAsInt()) + " (Hotspot ab " + threshold + ")");
    }

    private static String changes(int count) {
        return count + (count == 1 ? " Änderung" : " Änderungen");
    }

    private static String appendNote(String existing, String hint) {
        return existing == null || existing.isBlank() ? hint : existing.stripTrailing() + "\n" + hint;
    }
}
