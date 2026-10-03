package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.RunStatus;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.audit.AuditResultParser.ParsedFinding;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Eingeordnete Befunde, die ein gueltiger Snapshot nicht mehr meldet (TODOs b2e3da6a, 276ac76f): Regelwechsel je
 * Datei vererbt die Einordnung, eine ausgemusterte Regel schliesst sofort, sonst schliessen drei Laeufe ohne
 * Wiedersehen. Durch den {@link AuditSnapshotApplier}, damit Speichern und TODO-Abgleich mitgeprueft sind.
 */
@ExtendWith(MockitoExtension.class)
class AuditDispositionLifecycleTest {

    private static final String PROJECT = "proj-1";
    /** Mit Katalog: god-class (ohne arch.-Praefix) ist dort ausgemustert. */
    private static final String ARCHITECTURE = "mysuricates.architecture";
    /** Ohne Katalog: jeder ruleKey gilt, nur die Drei-Laeufe-Regel greift. */
    private static final String UNCHECKED = "test.audit-ohne-katalog";
    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");
    private static final Instant ACCEPTED_AT = Instant.parse("2026-09-01T10:00:00Z");

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private AuditReportRepository reportRepository;
    @Mock private ActivityLogService activityLog;
    @Mock private ManagedTodoWriter managedTodoWriter;

    private AuditSnapshotApplier applier;

    @BeforeEach
    void setUp() {
        AuditRuleKeyCatalog catalog = new AuditRuleKeyCatalog(new ObjectMapper());
        applier = new AuditSnapshotApplier(findingRepository, occurrenceRepository, new AuditAcceptancePolicy(),
                catalog, activityLog, managedTodoWriter, new AuditDispositionLifecycle(catalog, reportRepository));
        lenient().when(findingRepository.saveAll(any())).thenAnswer(inv -> {
            List<AuditFinding> saved = new ArrayList<>();
            int next = 0;
            for (AuditFinding finding : inv.<Iterable<AuditFinding>>getArgument(0)) {
                if (finding.getId() == null) finding.setId("f-new-" + next++);
                saved.add(finding);
            }
            return saved;
        });
    }

    // --- Ausgemusterte Regel ----------------------------------------------------------------------

    /** 276ac76f: god-class steht nicht mehr im Katalog — kein Report kann den Befund noch melden. */
    @Test
    void acceptedFindingOfARetiredRule_isResolvedWithANote() {
        AuditFinding legacy = disposed("f-legacy", ARCHITECTURE, "god-class", "HostClaudeService",
                AuditFindingStatus.ACCEPTED_RISK, NOW.minus(Duration.ofDays(1)));
        stock(ARCHITECTURE, legacy);

        apply(ARCHITECTURE, finding("arch.god-class", "com/automation/service/learning/LearningService.java"));

        assertThat(legacy.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(legacy.getResolvedAt()).isEqualTo(NOW);
        assertThat(legacy.getDispositionNote())
                .isEqualTo("Bewusst so\n" + AuditDispositionLifecycle.NOTE_RULE_RETIRED);
        assertThat(savedFindings()).contains(legacy);
        verify(managedTodoWriter).reconcile(argThat(touched -> touched.contains(legacy)));
        verify(reportRepository, never()).findValidGeneratedAtBefore(any(), any(), any(), any());
    }

    /** Auch eine verworfene Einordnung (DISMISSED) haengt sonst ewig in den Zaehlern. */
    @Test
    void dismissedFindingOfARetiredRule_isResolvedAsWell() {
        AuditFinding legacy = disposed("f-legacy", ARCHITECTURE, "frontend-complexity", "frontend/src/pages/Old.tsx",
                AuditFindingStatus.DISMISSED, NOW.minus(Duration.ofDays(1)));
        stock(ARCHITECTURE, legacy);

        apply(ARCHITECTURE);

        assertThat(legacy.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(legacy.getDispositionNote()).endsWith(AuditDispositionLifecycle.NOTE_RULE_RETIRED);
    }

    // --- Regelwechsel je Datei ----------------------------------------------------------------------

    /**
     * b2e3da6a: WorkflowExecutor war unter god-class verworfen, der naechste Lauf meldet die Datei unter
     * arch.high-coupling. Der neue Befund erbt die Einordnung (kein verwaltetes TODO), der alte ist ersetzt.
     */
    @Test
    void newFindingForTheSameFileUnderAnotherRule_inheritsTheDisposition() {
        AuditFinding legacy = disposed("f-legacy", ARCHITECTURE, "god-class", "WorkflowExecutor",
                AuditFindingStatus.DISMISSED, ACCEPTED_AT);
        stock(ARCHITECTURE, legacy);

        apply(ARCHITECTURE, finding("arch.high-coupling", "com/automation/service/workflow/WorkflowExecutor.java"));

        AuditFinding successor = savedFindings().getFirst();
        assertThat(successor.getRuleKey()).isEqualTo("arch.high-coupling");
        assertThat(successor.getStatus()).isEqualTo(AuditFindingStatus.DISMISSED);
        assertThat(successor.getDispositionAt()).isEqualTo(ACCEPTED_AT);
        assertThat(successor.getDispositionNote())
                .isEqualTo("Bewusst so\n" + AuditDispositionLifecycle.NOTE_INHERITED + "god-class (Regelwechsel)");
        assertThat(legacy.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(legacy.getDispositionNote())
                .endsWith(AuditDispositionLifecycle.NOTE_SUPERSEDED + "arch.high-coupling (Regelwechsel)");
        verify(managedTodoWriter).ensureAll(eq(List.of()), any());
    }

    /**
     * Eine geerbte Akzeptanz behaelt ihre Frist, vergleicht eine Verschlechterung aber mit der Evidence der neuen
     * Regel — die Zahlen der alten messen etwas anderes.
     */
    @Test
    void inheritedAcceptance_keepsItsDate_andTakesTheNewEvidenceAsSnapshot() {
        AuditFinding legacy = disposed("f-legacy", ARCHITECTURE, "coupling", "AssistantService",
                AuditFindingStatus.ACCEPTED_RISK, ACCEPTED_AT);
        legacy.setDispositionEvidence("12 Kollaborateure");
        stock(ARCHITECTURE, legacy);

        apply(ARCHITECTURE, finding("arch.god-class", "com/automation/service/assistant/AssistantService.java"));

        AuditFinding successor = savedFindings().getFirst();
        assertThat(successor.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(successor.getDispositionAt()).isEqualTo(ACCEPTED_AT);
        assertThat(successor.getDispositionEvidence()).isEqualTo("FAKT ncloc: 420");
    }

    /** Wird der alte Befund im selben Lauf noch gemeldet, sind es zwei Regeln — nichts wird vererbt. */
    @Test
    void stillReportedPredecessor_passesNothingOn() {
        AuditFinding accepted = disposed("f-acc", UNCHECKED, "rule.a", "Foo.java", AuditFindingStatus.ACCEPTED_RISK, NOW);
        accepted.setFingerprint(AuditSnapshotApplier.fingerprint(PROJECT, UNCHECKED, "rule.a", "Foo.java"));
        stock(UNCHECKED, accepted);

        apply(UNCHECKED, finding("rule.a", "Foo.java"), finding("rule.b", "Foo.java"));

        AuditFinding other = savedFindings().stream().filter(f -> "rule.b".equals(f.getRuleKey())).findFirst().orElseThrow();
        assertThat(other.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(accepted.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    /** {@code repository} bezeichnet bei jeder Regel etwas anderes: keine Vererbung ueber Nicht-Datei-subjects. */
    @Test
    void subjectThatNamesNoFile_passesNothingOn() {
        AuditFinding accepted = disposed("f-acc", UNCHECKED, "rule.a", "repository", AuditFindingStatus.ACCEPTED_RISK, NOW);
        stock(UNCHECKED, accepted);

        apply(UNCHECKED, finding("rule.b", "repository"));

        assertThat(savedFindings().getFirst().getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(accepted.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    /** Zwei Vorgaenger, zwei Nachfolger derselben Datei: jeder Vorgaenger vererbt hoechstens einmal. */
    @Test
    void eachPredecessorPassesItsDispositionOnAtMostOnce() {
        AuditFinding accepted = disposed("f-acc", UNCHECKED, "rule.a", "Foo.java", AuditFindingStatus.ACCEPTED_RISK, NOW);
        accepted.setDispositionAt(ACCEPTED_AT.plus(Duration.ofDays(1)));
        AuditFinding dismissed = disposed("f-dis", UNCHECKED, "rule.b", "Foo.java", AuditFindingStatus.DISMISSED, NOW);
        stock(UNCHECKED, accepted, dismissed);

        apply(UNCHECKED, finding("rule.c", "Foo.java"), finding("rule.d", "Foo.java"));

        assertThat(savedFindings().stream().filter(f -> f.getRuleKey().startsWith("rule.") && f.getId().startsWith("f-new"))
                .map(AuditFinding::getStatus).toList())
                .containsExactlyInAnyOrder(AuditFindingStatus.ACCEPTED_RISK, AuditFindingStatus.DISMISSED);
        assertThat(accepted.getDispositionNote()).endsWith(AuditDispositionLifecycle.NOTE_SUPERSEDED + "rule.c (Regelwechsel)");
        assertThat(dismissed.getDispositionNote()).endsWith(AuditDispositionLifecycle.NOTE_SUPERSEDED + "rule.d (Regelwechsel)");
    }

    /** Ein schwererer Nachfolger (HIGH statt LOW) erbt keine Einordnung — er ist neu zu bewerten. */
    @Test
    void moreSevereSuccessor_inheritsNothing() {
        AuditFinding dismissed = disposed("f-dis", ARCHITECTURE, "god-class", "WorkflowExecutor",
                AuditFindingStatus.DISMISSED, NOW);
        dismissed.setSeverity(AuditSeverity.LOW);
        stock(ARCHITECTURE, dismissed);

        apply(ARCHITECTURE, finding("arch.high-coupling", "com/automation/service/workflow/WorkflowExecutor.java"));

        assertThat(savedFindings().stream().filter(f -> "arch.high-coupling".equals(f.getRuleKey())).findFirst()
                .orElseThrow().getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(dismissed.getDispositionNote()).doesNotContain(AuditDispositionLifecycle.NOTE_SUPERSEDED);
    }

    /** Nur Pfade oder CamelCase-Klassen sind Dateien: ein einzelnes Wort oder eine Route vererbt nichts. */
    @Test
    void subjectsThatAreNoPathOrCamelCaseClass_passNothingOn() {
        AuditFinding word = disposed("f-word", UNCHECKED, "rule.a", "Scheduler", AuditFindingStatus.ACCEPTED_RISK, NOW);
        AuditFinding route = disposed("f-route", UNCHECKED, "rule.a", "GET /api/v1/workflows",
                AuditFindingStatus.ACCEPTED_RISK, NOW);
        stock(UNCHECKED, word, route);

        apply(UNCHECKED, finding("rule.b", "Scheduler"), finding("rule.b", "GET /api/v1/workflows"));

        assertThat(savedFindings().stream().filter(f -> "rule.b".equals(f.getRuleKey())).map(AuditFinding::getStatus))
                .containsOnly(AuditFindingStatus.OPEN);
    }

    // --- Teil-Messung: Kennzahl ohne Messwert in diesem Lauf --------------------------------------

    /** Steht der FAKT einer Kennzahl-Regel nicht im Faktenblatt, zaehlt der Lauf fuer ihren Befund nicht als Abwesenheit. */
    @Test
    void metricFindingWhoseFactWasNotMeasured_isNotRetired() {
        String performance = "mysuricates.performance";
        AuditFinding accepted = disposed("f-db", performance, "perf.db.size", "database_bytes",
                AuditFindingStatus.ACCEPTED_RISK, NOW.minus(Duration.ofDays(3)));
        stock(performance, accepted);
        lenient().when(reportRepository.findValidGeneratedAtBefore(eq(PROJECT), eq(performance), eq(NOW), any()))
                .thenReturn(List.of(NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(2))));

        apply(performance, Set.of("source_files_scanned"));

        assertThat(accepted.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    /** Gegenprobe: gemessen und drei Laeufe nicht gemeldet — dann schliesst er. */
    @Test
    void metricFindingWhoseFactWasMeasured_isRetiredAfterThreeRuns() {
        String performance = "mysuricates.performance";
        AuditFinding accepted = disposed("f-db", performance, "perf.db.size", "database_bytes",
                AuditFindingStatus.ACCEPTED_RISK, NOW.minus(Duration.ofDays(3)));
        stock(performance, accepted);
        when(reportRepository.findValidGeneratedAtBefore(eq(PROJECT), eq(performance), eq(NOW), any()))
                .thenReturn(List.of(NOW.minus(Duration.ofDays(1)), NOW.minus(Duration.ofDays(2))));

        apply(performance, Set.of("database_bytes"));

        assertThat(accepted.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
    }

    // --- Drei Laeufe ohne Wiedersehen -------------------------------------------------------------

    /**
     * Zwei gueltige Vorlaeufe vor diesem: wer zuletzt vor dem aelteren gesehen wurde, fehlt jetzt zum dritten Mal.
     * Wer im aelteren noch dabei war, fehlt erst zum zweiten Mal und bleibt eingeordnet.
     */
    @Test
    void disposedFindingAbsentForThreeRuns_isResolved_twoRunsAreNotEnough() {
        Instant previous = NOW.minus(Duration.ofDays(1));
        Instant beforePrevious = NOW.minus(Duration.ofDays(2));
        AuditFinding threeRuns = disposed("f-3", UNCHECKED, "rule.a", "Alt.java", AuditFindingStatus.ACCEPTED_RISK,
                NOW.minus(Duration.ofDays(3)));
        AuditFinding twoRuns = disposed("f-2", UNCHECKED, "rule.a", "Neuer.java", AuditFindingStatus.DISMISSED,
                beforePrevious);
        stock(UNCHECKED, threeRuns, twoRuns);
        when(reportRepository.findValidGeneratedAtBefore(eq(PROJECT), eq(UNCHECKED), eq(NOW), any()))
                .thenReturn(List.of(previous, beforePrevious));

        apply(UNCHECKED);

        assertThat(threeRuns.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(threeRuns.getResolvedAt()).isEqualTo(NOW);
        assertThat(threeRuns.getDispositionNote()).isEqualTo("Bewusst so\n" + AuditDispositionLifecycle.NOTE_NOT_SEEN);
        assertThat(twoRuns.getStatus()).isEqualTo(AuditFindingStatus.DISMISSED);
        assertThat(twoRuns.getDispositionNote()).isEqualTo("Bewusst so");
    }

    /** Solange es keine zwei gueltigen Vorlaeufe gibt, schliesst die Abwesenheit nichts Eingeordnetes. */
    @Test
    void withoutEnoughValidRuns_disposedFindingsStay() {
        AuditFinding accepted = disposed("f-acc", UNCHECKED, "rule.a", "Alt.java", AuditFindingStatus.ACCEPTED_RISK,
                NOW.minus(Duration.ofDays(30)));
        stock(UNCHECKED, accepted);
        when(reportRepository.findValidGeneratedAtBefore(eq(PROJECT), eq(UNCHECKED), eq(NOW), any()))
                .thenReturn(List.of(NOW.minus(Duration.ofDays(1))));

        apply(UNCHECKED);

        assertThat(accepted.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
    }

    // --- Hilfen ------------------------------------------------------------------------------------

    private AuditFinding disposed(String id, String auditKey, String ruleKey, String subject, AuditFindingStatus status,
                                  Instant lastSeenAt) {
        return AuditFinding.builder().id(id).projectId(PROJECT).auditKey(auditKey)
                .fingerprint("fp-" + id).ruleKey(ruleKey).subject(subject).title(subject)
                .severity(AuditSeverity.HIGH).status(status).dispositionNote("Bewusst so")
                .dispositionAt(ACCEPTED_AT).resolvedAt(ACCEPTED_AT).lastSeenAt(lastSeenAt).occurrenceCount(1).build();
    }

    private void stock(String auditKey, AuditFinding... findings) {
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, auditKey)).thenReturn(List.of(findings));
    }

    private static ParsedFinding finding(String ruleKey, String subject) {
        return new ParsedFinding(ruleKey, subject, AuditSeverity.HIGH, subject, null, "FAKT ncloc: 420", List.of());
    }

    private void apply(String auditKey, ParsedFinding... findings) {
        apply(auditKey, Set.of(), findings);
    }

    private void apply(String auditKey, Set<String> factNames, ParsedFinding... findings) {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "run-1", "wf-1", "Audit", PROJECT,
                "/tmp/p", RunStatus.COMPLETED, Map.of(), Map.of(), null);
        AuditReport report = AuditReport.builder().id("rep-1").projectId(PROJECT).auditKey(auditKey)
                .generatedAt(NOW).build();
        applier.apply(event, report, List.of(findings), factNames);
    }

    @SuppressWarnings("unchecked")
    private List<AuditFinding> savedFindings() {
        ArgumentCaptor<Iterable<AuditFinding>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(findingRepository, org.mockito.Mockito.atLeastOnce()).saveAll(captor.capture());
        List<AuditFinding> all = new ArrayList<>();
        captor.getAllValues().forEach(batch -> batch.forEach(all::add));
        return all;
    }
}
