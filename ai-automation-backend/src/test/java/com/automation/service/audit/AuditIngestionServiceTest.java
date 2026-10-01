package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.*;
import com.automation.service.activitylog.ActivityLogService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/** Verhalten der Ingestion gegenueber akzeptierten Risiken (ACCEPTED_RISK): Snapshot, Verschlechterung, Verfall. */
@ExtendWith(MockitoExtension.class)
class AuditIngestionServiceTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final String PROJECT = "proj-1";
    /**
     * Erfundener auditKey ohne Katalogeintrag: Die Tests hier pruefen die Akzeptanz-Politik, nicht
     * das ruleKey-Vokabular. Mit einem echten Schluessel wuerde jedes ergaenzte Vokabular sie
     * mitreissen (seit TODO a17e2d19 haben alle ausgelieferten Audits einen Katalog) — dann meldete
     * die Ingestion den Fixture-Key 'size.class' als unbekannt und verifyNoInteractions schluege an.
     */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    /** Ein Audit MIT ruleKey-Katalog (siehe {@code audit/rule-keys.json}). */
    private static final String COVERAGE_KEY = "mysuricates.test-coverage";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private AuditReportRepository reportRepository;
    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private ActivityLogService activityLog;

    private AuditIngestionService service;

    @BeforeEach
    void setUp() {
        AuditRuleKeyCatalog ruleKeyCatalog = new AuditRuleKeyCatalog(new ObjectMapper());
        service = new AuditIngestionService(runRepository, reportRepository,
                new AuditResultParser(new ObjectMapper()), ruleKeyCatalog, activityLog,
                new AuditSnapshotApplier(findingRepository, occurrenceRepository, new AuditAcceptancePolicy(),
                        ruleKeyCatalog, activityLog,
                        new ManagedTodoWriter(findingRepository, findingTodoRepository, todoRepository, eventPublisher)));
        when(reportRepository.save(any())).thenAnswer(inv -> {
            AuditReport report = inv.getArgument(0);
            if (report.getId() == null) report.setId("rep-" + UUID.randomUUID());
            return report;
        });
        lenient().when(findingRepository.saveAll(any())).thenAnswer(inv -> {
            List<AuditFinding> saved = new ArrayList<>();
            for (AuditFinding finding : inv.<Iterable<AuditFinding>>getArgument(0)) {
                if (finding.getId() == null) finding.setId("f-" + UUID.randomUUID());
                saved.add(finding);
            }
            return saved;
        });
    }

    /** Alle Findings, die {@code saveAll} bekam (3deaee34: der Applier speichert gesammelt). */
    @SuppressWarnings("unchecked")
    private List<AuditFinding> savedFindings() {
        ArgumentCaptor<Iterable<AuditFinding>> captor = ArgumentCaptor.forClass(Iterable.class);
        verify(findingRepository, atLeastOnce()).saveAll(captor.capture());
        List<AuditFinding> all = new ArrayList<>();
        captor.getAllValues().forEach(batch -> batch.forEach(all::add));
        return all;
    }

    private WorkflowRunCompletedEvent event(String runId) {
        return event(runId, AUDIT_KEY);
    }

    private WorkflowRunCompletedEvent event(String runId, String auditKey) {
        Map<String, Object> workflowMetadata = Map.of("audit", Map.of(
                "enabled", true, "key", auditKey, "category", "quality", "reportBlock", "report"));
        return new WorkflowRunCompletedEvent(this, runId, "wf-1", "Health Audit", PROJECT, "/tmp/p",
                RunStatus.COMPLETED, Map.of(), workflowMetadata, null);
    }

    private void stubRun(String runId, Instant completedAt, String evidence) {
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.class\","
                + "\"subject\":\"Big.java\",\"severity\":\"HIGH\",\"title\":\"Big.java ist zu gross\","
                + "\"evidence\":\"" + evidence + "\"}]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Health Audit").build())
                .status(RunStatus.COMPLETED).completedAt(completedAt).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
    }

    private void stubRunWithSubjects(String runId, String... subjects) {
        StringBuilder findings = new StringBuilder();
        for (String subject : subjects) {
            if (findings.length() > 0) findings.append(',');
            findings.append("{\"ruleKey\":\"size.class\",\"subject\":\"").append(subject)
                    .append("\",\"severity\":\"MEDIUM\",\"title\":\"").append(subject)
                    .append("\",\"evidence\":\"").append(subject).append("\"}");
        }
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[" + findings
                + "]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Health Audit").build())
                .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
    }

    /** 94fda6ef: gleiche Identitaet im selben Report — der zweite Befund faellt weg, aber nicht mehr still. */
    @Test
    void findingWithTheSameIdentity_isDroppedAndReported() {
        stubRunWithSubjects("run-1", "application.yml:key-a", "application.yml:key-b");

        service.ingest(event("run-1"));

        assertThat(savedFindings()).hasSize(1);
        verify(activityLog).warn(eq(ActivityLog.LogCategory.EXECUTION), contains("application.yml:key-b"),
                eq("AuditIngestionService"), eq("run-1"), eq("wf-1"));
    }

    @Test
    void kindPrefixedSubjects_keepSeparateIdentities() {
        stubRunWithSubjects("run-1", "package:com.automation.controller", "package:com.automation.service.workflow");

        service.ingest(event("run-1"));

        assertThat(savedFindings()).hasSize(2).extracting(AuditFinding::getFingerprint).doesNotHaveDuplicates();
        verify(activityLog, never()).warn(any(), contains("gleiche Identitaet"), any(), any(), any());
    }

    /** Ein akzeptiertes Finding; {@code inSnapshot} = der neue Report enthaelt es (gleicher Fingerprint). */
    private AuditFinding acceptedFinding(String snapshotEvidence, Instant acceptedAt, boolean inSnapshot) {
        AuditFinding finding = AuditFinding.builder()
                .id("f-accepted").projectId(PROJECT).auditKey(AUDIT_KEY).fingerprint("fp").ruleKey("size.class")
                .subject("Big.java").severity(AuditSeverity.HIGH).title("Big.java ist zu gross")
                .evidence(snapshotEvidence).status(AuditFindingStatus.ACCEPTED_RISK)
                .dispositionNote("Bewusst gross").dispositionEvidence(snapshotEvidence).dispositionAt(acceptedAt)
                .resolvedAt(acceptedAt).firstSeenReportId("rep-0").lastSeenReportId("rep-0")
                .firstSeenRunId("run-0").lastSeenRunId("run-0").firstSeenAt(ACCEPTED_AT).lastSeenAt(ACCEPTED_AT)
                .occurrenceCount(2).build();
        // Im Snapshot heisst: derselbe Fingerprint wie der Befund im Envelope (size.class / Big.java).
        finding.setFingerprint(inSnapshot ? AuditSnapshotApplier.fingerprint(PROJECT, AUDIT_KEY, "size.class", "Big.java")
                : "fp-anderer-befund");
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, AUDIT_KEY)).thenReturn(List.of(finding));
        return finding;
    }

    @Test
    void acceptedFinding_reappearingWithoutDegradation_staysAcceptedAndCreatesNoTodo() {
        AuditFinding finding = acceptedFinding("Big.java: 320 LOC", ACCEPTED_AT, true);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(7)), "Big.java: 350 LOC");

        service.ingest(event("run-1"));

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(finding.getEvidence()).isEqualTo("Big.java: 350 LOC");
        assertThat(finding.getDispositionEvidence()).isEqualTo("Big.java: 320 LOC");
        assertThat(finding.getOccurrenceCount()).isEqualTo(3);
        verify(todoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void acceptedFinding_degradedByMoreThanTenPercent_isReopenedWithHintAndManagedTodo() {
        AuditFinding finding = acceptedFinding("Big.java: 320 LOC", ACCEPTED_AT, true);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(7)), "Big.java: 400 LOC");
        when(todoRepository.save(any())).thenAnswer(inv -> {
            Todo todo = inv.getArgument(0);
            todo.setId("todo-1");
            return todo;
        });

        service.ingest(event("run-1"));

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(finding.getResolvedAt()).isNull();
        assertThat(finding.getDispositionNote()).isEqualTo("Bewusst gross\nVerschlechtert: 320 LOC → 400 LOC");
        assertThat(finding.getDispositionEvidence()).as("snapshot stays for traceability").isEqualTo("Big.java: 320 LOC");
        verify(todoRepository).save(any());
        verify(eventPublisher).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void acceptedFinding_withoutComparableNumbers_staysAccepted() {
        AuditFinding finding = acceptedFinding("Klasse unuebersichtlich", ACCEPTED_AT, true);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(7)), "Klasse noch unuebersichtlicher, 900 LOC");

        service.ingest(event("run-1"));

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        verify(todoRepository, never()).save(any());
    }

    @Test
    void acceptedFinding_olderThan180Days_isMovedToAwaitingVerificationWithoutTodo() {
        AuditFinding finding = acceptedFinding("Big.java: 320 LOC", ACCEPTED_AT, true);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(181)), "Big.java: 320 LOC");

        service.ingest(event("run-1"));

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(finding.getDispositionNote()).isEqualTo("Bewusst gross\nWiedervorlage nach 180 Tagen");
        assertThat(finding.getDispositionAt()).isEqualTo(ACCEPTED_AT);
        verify(todoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TodoCreatedEvent.class));
    }

    /**
     * ruleKey-Drift erzeugt sonst Wiedergaenger-Findings: der Envelope-Key wird auf den
     * Katalogeintrag normalisiert (Audit-TODO 8fc9a401) — ohne Meldung, das ist der Normalfall.
     */
    @Test
    void driftedRuleKey_isNormalizedOntoTheCatalogEntryWithoutWarning() {
        stubCoverageRun("run-1", "Test-Coverage.Untested_Class");

        service.ingest(event("run-1", COVERAGE_KEY));

        assertThat(savedFinding().getRuleKey()).isEqualTo("test-coverage.untested-class");
        verifyNoInteractions(activityLog);
    }

    /** Der verworfene Report nennt den Grund im Activity-Log, nicht nur im Server-Log. */
    @Test
    void unknownRuleKey_isReportedToTheActivityLog() {
        stubCoverageRun("run-1", "coverage.mystery");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(activityLog).warn(eq(ActivityLog.LogCategory.EXECUTION), message.capture(),
                anyString(), eq("run-1"), anyString());
        assertThat(message.getValue()).contains("coverage.mystery").contains(COVERAGE_KEY);
    }

    /** Derselbe unbekannte Key in mehreren Findings eines Runs meldet sich genau einmal. */
    @Test
    void unknownRuleKey_isReportedOncePerRunAndKey() {
        stubCoverageRun("run-1", "coverage.mystery", "coverage.mystery", "coverage.other");

        service.ingest(event("run-1", COVERAGE_KEY));

        // Beide unbekannten Keys stehen in EINER Meldung des verworfenen Reports — je Run eine.
        verify(activityLog).warn(any(), contains("coverage.mystery"), anyString(), anyString(), anyString());
    }

    /**
     * Frueher wurde ein Finding mit unbekanntem Key uebernommen und am Finding markiert
     * (TODO ffa6d846). Seit f57945e2 faellt der <b>ganze Report</b> aus: Ein gedrifteter Key macht
     * den Fingerprint unzuverlaessig, und ein Snapshot mit unzuverlaessigen Identitaeten loest die
     * Vorgaengergeneration auf, statt sie wiederzuerkennen. Die Markierung war die Zwischenstufe,
     * solange es keine haertere Konsequenz gab.
     */
    @Test
    void unknownRuleKey_verwirftDenGanzenReport() {
        stubCoverageRun("run-1", "coverage.mystery");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<AuditReport> report = ArgumentCaptor.forClass(AuditReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.INVALID);
        verify(findingRepository, never()).saveAll(any());
    }

    /** Ein bekannter Key markiert nicht — auch nicht der auf den Katalog normalisierte. */
    @Test
    void knownAndDriftedRuleKeys_doNotMarkTheFinding() {
        stubCoverageRun("run-1", "Test-Coverage.Untested_Class");

        service.ingest(event("run-1", COVERAGE_KEY));

        assertThat(savedFinding().isUnknownRuleKey()).isFalse();
    }

    /**
     * Die Markierung faellt von selbst weg, sobald der Katalog den Key kennt: Das Flag wird bei
     * JEDEM Ingest neu gesetzt, nicht nur beim Anlegen. Sonst bliebe ein einmal markiertes
     * Finding fuer immer markiert und die Anzeige waere nach der Katalog-Ergaenzung falsch.
     */
    @Test
    void markIsClearedOnTheNextIngestOnceTheCatalogKnowsTheKey() {
        AuditFinding marked = AuditFinding.builder()
                .id("f-marked").projectId(PROJECT).auditKey(COVERAGE_KEY)
                .fingerprint(AuditSnapshotApplier.fingerprint(PROJECT, COVERAGE_KEY, "test-coverage.untested-class", "Klasse0.java"))
                .ruleKey("test-coverage.untested-class").subject("Big.java")
                .severity(AuditSeverity.LOW).title("t").status(AuditFindingStatus.OPEN)
                .firstSeenReportId("r0").lastSeenReportId("r0").firstSeenRunId("run-0").lastSeenRunId("run-0")
                .firstSeenAt(ACCEPTED_AT).lastSeenAt(ACCEPTED_AT).unknownRuleKey(true).build();
        stubCoverageRunEnvelope("run-1", "test-coverage.untested-class");
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, COVERAGE_KEY)).thenReturn(List.of(marked));

        service.ingest(event("run-1", COVERAGE_KEY));

        assertThat(savedFinding().getId()).as("das vorhandene Finding wurde fortgeschrieben, nicht ersetzt")
                .isEqualTo("f-marked");
        assertThat(marked.isUnknownRuleKey()).isFalse();
    }

    /** Ein Audit ohne Katalogeintrag wird nicht geprueft — die uebrigen Audits vergeben frei. */
    @Test
    void anAuditWithoutCatalogEntry_isNotChecked() {
        acceptedFinding("Big.java: 320 LOC", ACCEPTED_AT, true);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(7)), "Big.java: 350 LOC");

        service.ingest(event("run-1"));

        verifyNoInteractions(activityLog);
    }

    private AuditFinding savedFinding() {
        return savedFindings().getLast();
    }

    /** Ein Coverage-Run mit je einem LOW-Finding pro ruleKey (LOW erzeugt kein verwaltetes TODO). */
    /** Coverage-Run ohne Bestand: jedes Finding ist neu. */
    private void stubCoverageRun(String runId, String... ruleKeys) {
        stubCoverageRunEnvelope(runId, ruleKeys);
    }

    private void stubCoverageRunEnvelope(String runId, String... ruleKeys) {
        StringBuilder findings = new StringBuilder();
        for (int i = 0; i < ruleKeys.length; i++) {
            findings.append(i > 0 ? "," : "").append("{\"ruleKey\":\"").append(ruleKeys[i])
                    .append("\",\"subject\":\"Klasse").append(i).append(".java\",\"severity\":\"LOW\","
                            + "\"title\":\"ungetestet\"}");
        }
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":["
                + findings + "]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Coverage Audit").build())
                .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
        lenient().when(findingRepository.findByProjectIdAndAuditKey(PROJECT, COVERAGE_KEY)).thenReturn(List.of());
    }

    @Test
    void acceptedFinding_absentFromSnapshot_isLeftUntouched() {
        AuditFinding finding = acceptedFinding("Big.java: 320 LOC", ACCEPTED_AT, false);
        stubRun("run-1", ACCEPTED_AT.plus(Duration.ofDays(400)), "Other.java: 10 LOC");
        when(todoRepository.save(any())).thenAnswer(inv -> {
            Todo todo = inv.getArgument(0);
            todo.setId("todo-new");
            return todo;
        });

        service.ingest(event("run-1"));

        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(finding.getDispositionNote()).isEqualTo("Bewusst gross");
        verify(todoRepository).save(any());
    }

    /** Run mit Faktenblatt-Block: Beleg-Pruefung greift (TODO a94b56a0). */
    private void stubRunWithFactSheet(String runId, String evidence) {
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\"size.class\","
                + "\"subject\":\"Big.java\",\"severity\":\"HIGH\",\"title\":\"Big.java ist zu gross\","
                + "\"evidence\":\"" + evidence + "\"}]} -->\n# Report";
        StepResult facts = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                .output("## FAKTENBLATT\nFAKT files_over_300: 19\n")
                .metadata(new HashMap<>(Map.of("blockName", "messung"))).build();
        StepResult report = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Health Audit").build())
                .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT).steps(new ArrayList<>(List.of(facts, report))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
        // findingRepository.saveAll ist in setUp gestubt; findBy... liefert per Mockito-Default leer.
    }

    @Test
    void findingOhneBelegImFaktenblattWirdMarkiertUndErzeugtKeinTodo() {
        stubRunWithFactSheet("run-1", "TODO 5594f26c, FAKT aus_dem_letzten_report: 7");

        service.ingest(event("run-1"));

        assertThat(savedFindings().getFirst().isUnverifiedEvidence()).isTrue();
        verify(todoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void findingMitBelegAusDemFaktenblattLaeuftWieBisher() {
        stubRunWithFactSheet("run-1", "FAKT files_over_300: 19");
        when(todoRepository.save(any())).thenAnswer(inv -> {
            Todo todo = inv.getArgument(0);
            todo.setId("todo-1");
            return todo;
        });

        service.ingest(event("run-1"));

        assertThat(savedFindings().getFirst().isUnverifiedEvidence()).isFalse();
        verify(todoRepository).save(any());
    }

    /** Ein Run mit frei waehlbarem ruleKey — fuer die Katalogpruefung. */
    private void stubRunWithRuleKey(String runId, String ruleKey) {
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\""
                + ruleKey + "\",\"subject\":\"Big.java\",\"severity\":\"HIGH\",\"title\":\"t\","
                + "\"evidence\":\"e\"}]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Coverage").build())
                .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
        // HIGH erzeugt ein verwaltetes TODO — nur im gueltigen Fall, deshalb lenient.
        lenient().when(todoRepository.save(any())).thenAnswer(inv -> {
            Todo todo = inv.getArgument(0);
            if (todo.getId() == null) todo.setId("todo-" + UUID.randomUUID());
            return todo;
        });
    }

    /**
     * Gedriftete ruleKeys tragen die richtige <i>Anzahl</i> Findings und passieren deshalb den
     * zaehlenden Gate-Block. Die Ingestion legte daraufhin lauter neue Identitaeten an und schloss
     * die Vorgaengergeneration als RESOLVED — ebenso viele Wiedergaenger (TODO f57945e2).
     */
    @Test
    void unbekannterRuleKeyMachtDenGanzenReportUngueltig() {
        stubRunWithRuleKey("run-1", "test-coverage.voellig-erfunden");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<AuditReport> report = ArgumentCaptor.forClass(AuditReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.INVALID);
        assertThat(report.getValue().getIngestionError())
                .contains("test-coverage.voellig-erfunden").contains("test-coverage.untested-class");
        verify(findingRepository, never()).saveAll(any());
        verify(todoRepository, never()).save(any());
    }

    /** Ein Key aus dem Katalog laeuft unveraendert durch. */
    @Test
    void bekannterRuleKeyBleibtGueltig() {
        stubRunWithRuleKey("run-1", "test-coverage.untested-class");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<AuditReport> report = ArgumentCaptor.forClass(AuditReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.VALID);
        assertThat(savedFindings()).isNotEmpty();
    }

    /** Ein Audit ohne Katalogeintrag vergibt seine Keys frei — unveraendertes Verhalten. */
    @Test
    void ohneKatalogBleibtDerReportGueltig() {
        stubRunWithRuleKey("run-1", "was.auch.immer");

        service.ingest(event("run-1", AUDIT_KEY));

        ArgumentCaptor<AuditReport> report = ArgumentCaptor.forClass(AuditReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.VALID);
        assertThat(savedFindings()).isNotEmpty();
    }

    /**
     * Die Naht zum {@link AuditSnapshotApplier} (Audit-TODO aac5fbf7): Der Service prueft und
     * speichert den Report und reicht nur einen gueltigen weiter — mit den Findings des Envelopes und
     * den Messwerten des Laufs.
     */
    @Nested
    class HandOverToTheApplier {

        @Mock private AuditSnapshotApplier applier;
        private AuditIngestionService withMockedApplier;

        /** Nach dem aeusseren setUp: erst dann stehen die Mocks, die der Service bekommt. */
        @BeforeEach
        void mockTheApplier() {
            withMockedApplier = new AuditIngestionService(runRepository, reportRepository,
                    new AuditResultParser(new ObjectMapper()), new AuditRuleKeyCatalog(new ObjectMapper()),
                    activityLog, applier);
        }

        @Test
        void validReport_isStoredAndHandedOverWithItsFindingsAndTheRunsFacts() {
            stubRunWithFactSheet("run-1", "FAKT files_over_300: 19");
            WorkflowRunCompletedEvent event = event("run-1");

            withMockedApplier.ingest(event);

            ArgumentCaptor<AuditReport> stored = ArgumentCaptor.forClass(AuditReport.class);
            verify(reportRepository).save(stored.capture());
            assertThat(stored.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.VALID);
            verify(applier).apply(eq(event), eq(stored.getValue()),
                    argThat(findings -> findings.size() == 1 && "Big.java".equals(findings.getFirst().subject())),
                    eq(Set.of("files_over_300")));
        }

        @Test
        void invalidReport_neverReachesTheApplier() {
            stubRunWithRuleKey("run-1", "test-coverage.voellig-erfunden");

            withMockedApplier.ingest(event("run-1", COVERAGE_KEY));

            ArgumentCaptor<AuditReport> stored = ArgumentCaptor.forClass(AuditReport.class);
            verify(reportRepository).save(stored.capture());
            assertThat(stored.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.INVALID);
            verifyNoInteractions(applier);
        }
    }
}
