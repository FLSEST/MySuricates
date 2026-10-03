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
 * Was aus eingeordneten Befunden ({@code ACCEPTED_RISK}, {@code DISMISSED}) wird, die ein gueltiger,
 * vollstaendiger Snapshot nicht mehr meldet (TODOs b2e3da6a, 276ac76f). Offene Befunde fallen in dem Fall
 * sofort auf {@code RESOLVED}; eingeordnete blieben bisher fuer immer stehen — in den Akzeptiert-Zaehlern
 * und ohne 180-Tage-Wiedervorlage, weil {@link AuditAcceptancePolicy} nur bei einem Wiedersehen bewertet.
 *
 * <ul>
 *   <li><b>Regelwechsel je Datei:</b> Meldet derselbe Lauf fuer dasselbe (normalisierte) Datei-subject
 *       einen neuen Befund unter einem anderen ruleKey, erbt der neue die Einordnung samt Notiz, der alte
 *       wird {@code RESOLVED} („Ersetzt durch …“). Jeder Vorgaenger vererbt hoechstens einmal; ein
 *       Nachfolger mit hoeherer Schwere (CRITICAL/HIGH) erbt nichts und wird neu bewertet. Nur fuer subjects, die eine Datei oder Klasse nennen —
 *       {@code repository} oder ein FAKT-Name bezeichnen bei jeder Regel etwas anderes.</li>
 *   <li><b>Regel ausgemustert:</b> Steht der ruleKey nicht mehr im Katalog des Audits, kann ihn kein
 *       gueltiger Report mehr melden — {@code RESOLVED} mit {@value #NOTE_RULE_RETIRED}.</li>
 *   <li><b>Nicht mehr gesehen:</b> Fehlt der Befund in {@value #ABSENT_RUNS} gueltigen Laeufen in Folge
 *       (dieser eingeschlossen), wird er {@code RESOLVED} mit {@link #NOTE_NOT_SEEN} — bei einer Kennzahl-Regel
 *       nur in einem Lauf, der ihren FAKT gemessen hat. Die Prompts fuehren
 *       eingeordnete Befunde im Envelope weiter, solange sie bestehen; ein einzelner Lauf, der einen
 *       vergisst, schliesst ihn also noch nicht.</li>
 * </ul>
 * Reine DB-Arbeit in der Transaktion von {@link AuditIngestionService#ingest}; Speichern und das Schliessen
 * verwalteter TODOs bleiben beim {@link AuditSnapshotApplier}.
 */
@Slf4j
@Component
@RequiredArgsConstructor
class AuditDispositionLifecycle {

    /** So viele gueltige Laeufe in Folge ohne Wiedersehen, dann gilt ein eingeordneter Befund als erledigt. */
    static final int ABSENT_RUNS = 3;
    static final String NOTE_RULE_RETIRED = "Regel nicht mehr im Katalog";
    static final String NOTE_NOT_SEEN = "Nicht mehr gemessen/ersetzt: " + ABSENT_RUNS + " Läufe ohne Wiedersehen";
    static final String NOTE_INHERITED = "Übernommen von ";
    static final String NOTE_SUPERSEDED = "Ersetzt durch ";
    private static final String RULE_CHANGE = " (Regelwechsel)";

    /**
     * Datei-subject: ein Pfad (mit {@code /} oder Endung) oder ein CamelCase-Klassenname mit mindestens zwei
     * Grossbuchstaben ({@code WorkflowExecutor}). Ein einzelnes Wort ({@code Scheduler}), eine Route mit
     * Leerzeichen oder ein {@code <art>:<wert>} bezeichnen bei jeder Regel etwas anderes.
     */
    private static final Pattern PATH = Pattern.compile("[^\\s:]*/[^\\s:]*|[^\\s:/]+\\.[A-Za-z0-9]+");
    /** Form eines FAKT-Namens: klein, mit Unterstrich ({@code database_bytes}, {@code frontend_tsc_errors}). */
    private static final Pattern FACT_NAME = Pattern.compile("[a-z][a-z0-9]*(?:_[a-z0-9]+)+");
    private static final Pattern CAMEL_CASE_CLASS = Pattern.compile("[A-Z][a-z0-9_]+[A-Z][A-Za-z0-9_]*");

    private static final Comparator<AuditFinding> LATEST_DISPOSITION_FIRST = Comparator
            .comparing(AuditDispositionLifecycle::dispositionTime, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(AuditFinding::getLastSeenAt, Comparator.nullsLast(Comparator.reverseOrder()));

    private final AuditRuleKeyCatalog ruleKeyCatalog;
    private final AuditReportRepository reportRepository;

    /**
     * Neue Befunde dieses Laufs erben die Einordnung eines nicht mehr gemeldeten Vorgaengers mit demselben
     * Datei-subject und anderem ruleKey.
     *
     * @param created  in diesem Lauf neu angelegte Befunde (Felder schon gesetzt, noch ungespeichert)
     * @param absent   bekannte Befunde, die dieser Lauf nicht gemeldet hat
     * @return die abgeloesten Vorgaenger, jetzt {@code RESOLVED}
     */
    List<AuditFinding> inheritOnRuleChange(List<AuditFinding> created, List<AuditFinding> absent, Instant observedAt) {
        List<AuditFinding> predecessors = new ArrayList<>(absent.stream()
                .filter(finding -> isDisposed(finding.getStatus()) && fileSubject(finding) != null)
                .sorted(LATEST_DISPOSITION_FIRST)
                .toList());
        if (predecessors.isEmpty() || created.isEmpty()) {
            return List.of();
        }
        // Vorgaenger → ruleKeys seiner Nachfolger (AuditFinding vergleicht per Identitaet)
        Map<AuditFinding, Set<String>> superseded = new LinkedHashMap<>();
        for (AuditFinding successor : created) {
            String subject = fileSubject(successor);
            if (subject == null) continue;
            // 1:1 — ein Vorgaenger vererbt hoechstens einmal, danach ist er aus dem Pool.
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
     * Schliesst eingeordnete Befunde, die dieser gueltige Snapshot nicht gemeldet hat: ausgemusterte Regel
     * sofort, sonst nach {@value #ABSENT_RUNS} Laeufen ohne Wiedersehen.
     *
     * @param absent    bekannte Befunde, die dieser Lauf nicht gemeldet hat (nicht eingeordnete werden uebergangen)
     * @param factNames die FAKT-Namen dieses Laufs: eine Kennzahl-Regel, deren FAKT fehlt, war nicht messbar —
     *                  ihr Befund bleibt (Teil-Messung)
     * @return die jetzt auf {@code RESOLVED} gesetzten Befunde
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
     * Wer zuletzt vor diesem Zeitpunkt gesehen wurde, fehlt im aktuellen und den {@value #ABSENT_RUNS} − 1
     * gueltigen Laeufen davor: der Zeitpunkt des aeltesten dieser Vorlaeufe. {@code null}, solange es so viele
     * Vorlaeufe noch nicht gibt.
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
     * Ein Nachfolger mit hoher Schwere (CRITICAL/HIGH), die ueber der des Vorgaengers liegt, erbt nichts: Die
     * Einordnung galt einem leichteren Befund, der neue ist neu zu bewerten.
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
        // Vergleichsbasis der Verschlechterungsregel: die Evidence der neuen Regel, nicht die der alten —
        // deren Zahlen messen etwas anderes. Die Frist der Akzeptanz laeuft weiter.
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
     * Kennzahl-Regel (festes subject in FAKT-Form, {@code database_bytes}), deren FAKT im Faktenblatt dieses Laufs
     * fehlt: Der Lauf hat sie nicht gemessen, sein Schweigen ist keine Abwesenheit. Feste subjects wie
     * {@code repository} oder {@code LICENSE} sind keine FAKT-Namen und bleiben unberuehrt.
     */
    private boolean isUnmeasuredMetric(AuditFinding finding, Set<String> factNames) {
        String fixed = ruleKeyCatalog.fixedSubject(finding.getAuditKey(), finding.getRuleKey());
        return fixed != null && FACT_NAME.matcher(fixed).matches() && !factNames.contains(fixed);
    }

    /** Das normalisierte subject, wenn es eine Datei oder Klasse nennt und die Regel keinen festen Wert hat. */
    private String fileSubject(AuditFinding finding) {
        if (ruleKeyCatalog.fixedSubject(finding.getAuditKey(), finding.getRuleKey()) != null) {
            return null;
        }
        String subject = subjectOf(finding);
        if (subject.indexOf(':') >= 0) {
            return null; // <art>:<wert>
        }
        // Pfad oder Endung zaehlen in der gemeldeten Form: der Normalizer macht aus Foo.java den Klassennamen Foo.
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
