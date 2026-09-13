package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Regeln fuer akzeptierte Audit-Risiken (ACCEPTED_RISK).
 * <ul>
 *   <li><b>Beweis-Snapshot:</b> Beim Akzeptieren wird der aktuelle Evidence-Text samt Zeitpunkt am
 *       Finding eingefroren ({@link #accept}).</li>
 *   <li><b>Verschlechterung:</b> Taucht das Finding erneut auf und liegt ein messbarer Wert der neuen
 *       Evidence mehr als {@value #DEGRADATION_THRESHOLD_PERCENT} % ueber dem Snapshot, wird es wieder
 *       OPEN; die Notiz bekommt einen "Verschlechtert"-Hinweis, der Snapshot bleibt erhalten.</li>
 *   <li><b>Verfall:</b> Ist die Akzeptanz aelter als {@link #REVIEW_INTERVAL}, geht das Finding beim
 *       naechsten Auftauchen auf AWAITING_VERIFICATION ("Wiedervorlage"). Danach gelten die normalen
 *       Ingestion-Regeln; ein erneutes Akzeptieren setzt Snapshot und Frist neu.</li>
 * </ul>
 * Ohne vergleichbare Zahlen findet keine Wiedereroeffnung statt.
 */
@Component
public class AuditAcceptancePolicy {
    public static final int DEGRADATION_THRESHOLD_PERCENT = 10;
    public static final Duration REVIEW_INTERVAL = Duration.ofDays(180);
    static final String REVIEW_NOTE = "Wiedervorlage nach 180 Tagen";
    static final String DEGRADED_PREFIX = "Verschlechtert: ";

    public enum Outcome { UNCHANGED, REOPENED, REVIEW_DUE }

    /** Friert Evidence und Zeitpunkt der Akzeptanz am Finding ein. */
    public void accept(AuditFinding finding, Instant acceptedAt) {
        finding.setDispositionEvidence(finding.getEvidence());
        finding.setDispositionAt(acceptedAt);
    }

    /**
     * Bewertet ein erneut aufgetauchtes Finding gegen seinen Snapshot. Mutiert Status und Notiz,
     * ist fuer alle Status ausser ACCEPTED_RISK ein No-op.
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

    /** @return "alt → neu" je gemeinsamer Einheit, deren Wert um mehr als den Schwellwert gestiegen ist. */
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
        return result;
    }

    private static String appendNote(String existing, String hint) {
        return existing == null || existing.isBlank() ? hint : existing.stripTrailing() + "\n" + hint;
    }
}
