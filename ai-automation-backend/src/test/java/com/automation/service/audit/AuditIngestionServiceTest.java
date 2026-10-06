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

/** Behavior of the ingestion towards accepted risks (ACCEPTED_RISK): snapshot, deterioration, expiry. */
@ExtendWith(MockitoExtension.class)
class AuditIngestionServiceTest {
    private static final Instant ACCEPTED_AT = Instant.parse("2026-03-01T10:00:00Z");
    private static final String PROJECT = "proj-1";
    /**
     * Made-up auditKey without a catalog entry: the tests here check the acceptance policy, not
     * the ruleKey vocabulary. With a real key every added vocabulary entry would drag them
     * along (since TODO a17e2d19 all shipped audits have a catalog) — the ingestion would then report
     * the fixture key 'size.class' as unknown and verifyNoInteractions would fail.
     */
    private static final String AUDIT_KEY = "test.audit-ohne-katalog";
    /** An audit WITH a ruleKey catalog (see {@code audit/rule-keys.json}). */
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
                        new ManagedTodoWriter(findingRepository, findingTodoRepository, todoRepository, eventPublisher, activityLog),
                        new AuditDispositionLifecycle(ruleKeyCatalog, reportRepository)));
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

    /** All findings that {@code saveAll} received (3deaee34: the applier saves in bulk). */
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

    /** 94fda6ef: same identity in the same report — the second finding is dropped, but no longer silently. */
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

    /** An accepted finding; {@code inSnapshot} = the new report contains it (same fingerprint). */
    private AuditFinding acceptedFinding(String snapshotEvidence, Instant acceptedAt, boolean inSnapshot) {
        AuditFinding finding = AuditFinding.builder()
                .id("f-accepted").projectId(PROJECT).auditKey(AUDIT_KEY).fingerprint("fp").ruleKey("size.class")
                .subject("Big.java").severity(AuditSeverity.HIGH).title("Big.java ist zu gross")
                .evidence(snapshotEvidence).status(AuditFindingStatus.ACCEPTED_RISK)
                .dispositionNote("Bewusst gross").dispositionEvidence(snapshotEvidence).dispositionAt(acceptedAt)
                .resolvedAt(acceptedAt).firstSeenReportId("rep-0").lastSeenReportId("rep-0")
                .firstSeenRunId("run-0").lastSeenRunId("run-0").firstSeenAt(ACCEPTED_AT).lastSeenAt(ACCEPTED_AT)
                .occurrenceCount(2).build();
        // In the snapshot means: the same fingerprint as the finding in the envelope (size.class / Big.java).
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
     * ruleKey drift otherwise produces recurrence findings: the envelope key is normalized to the
     * catalog entry (audit TODO 8fc9a401) — without a message, that is the normal case.
     */
    @Test
    void driftedRuleKey_isNormalizedOntoTheCatalogEntryWithoutWarning() {
        stubCoverageRun("run-1", "Test-Coverage.Untested_Class");

        service.ingest(event("run-1", COVERAGE_KEY));

        assertThat(savedFinding().getRuleKey()).isEqualTo("test-coverage.untested-class");
        verifyNoInteractions(activityLog);
    }

    /** The rejected report names the reason in the activity log, not only in the server log. */
    @Test
    void unknownRuleKey_isReportedToTheActivityLog() {
        stubCoverageRun("run-1", "coverage.mystery");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(activityLog).warn(eq(ActivityLog.LogCategory.EXECUTION), message.capture(),
                anyString(), eq("run-1"), anyString());
        assertThat(message.getValue()).contains("coverage.mystery").contains(COVERAGE_KEY);
    }

    /** The same unknown key in several findings of a run is reported exactly once. */
    @Test
    void unknownRuleKey_isReportedOncePerRunAndKey() {
        stubCoverageRun("run-1", "coverage.mystery", "coverage.mystery", "coverage.other");

        service.ingest(event("run-1", COVERAGE_KEY));

        // Both unknown keys appear in ONE message of the rejected report — one per run.
        verify(activityLog).warn(any(), contains("coverage.mystery"), anyString(), anyString(), anyString());
    }

    /**
     * Formerly a finding with an unknown key was accepted and flagged on the finding
     * (TODO ffa6d846). Since f57945e2 the <b>whole report</b> is dropped: a drifted key makes
     * the fingerprint unreliable, and a snapshot with unreliable identities resolves the
     * previous generation instead of recognizing it. The flag was the intermediate stage
     * as long as there was no harsher consequence.
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

    /** A known key does not flag — not even the one normalized to the catalog. */
    @Test
    void knownAndDriftedRuleKeys_doNotMarkTheFinding() {
        stubCoverageRun("run-1", "Test-Coverage.Untested_Class");

        service.ingest(event("run-1", COVERAGE_KEY));

        assertThat(savedFinding().isUnknownRuleKey()).isFalse();
    }

    /**
     * The flag disappears on its own as soon as the catalog knows the key: the flag is set anew on
     * EVERY ingest, not only on creation. Otherwise a once-flagged
     * finding would stay flagged forever and the display would be wrong after the catalog addition.
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

    /** An audit without a catalog entry is not checked — the other audits assign freely. */
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

    /** A coverage run with one LOW finding per ruleKey (LOW produces no managed TODO). */
    /** Coverage run without existing data: every finding is new. */
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

    /**
     * 276ac76f: health findings from the old rule schema (complexity.god-class-count) stayed in the counters as accepted
     * after every valid run. The next valid, complete snapshot closes them.
     */
    @Test
    void acceptedFindingWithARuleKeyNoLongerInTheCatalog_isResolvedByTheNextValidSnapshot() {
        String health = "mysuricates.health";
        AuditFinding legacy = AuditFinding.builder()
                .id("f-legacy").projectId(PROJECT).auditKey(health).fingerprint("fp-legacy")
                .ruleKey("complexity.god-class-count").subject("god_class_count").severity(AuditSeverity.MEDIUM)
                .title("Zu viele grosse Klassen").status(AuditFindingStatus.ACCEPTED_RISK)
                .dispositionNote("Bekannt, Abbau laeuft").dispositionAt(ACCEPTED_AT).resolvedAt(ACCEPTED_AT)
                .firstSeenAt(ACCEPTED_AT).lastSeenAt(ACCEPTED_AT).occurrenceCount(3).build();
        when(findingRepository.findByProjectIdAndAuditKey(PROJECT, health)).thenReturn(List.of(legacy));
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":"
                + "\"health.lint-findings\",\"subject\":\"frontend_lint_errors\",\"severity\":\"LOW\","
                + "\"title\":\"ESLint meldet 2 Fehler\"}]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        Instant now = ACCEPTED_AT.plus(Duration.ofDays(20));
        when(runRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(WorkflowRun.builder().id("run-1")
                .workflow(Workflow.builder().name("Health").build()).status(RunStatus.COMPLETED).completedAt(now)
                .steps(new ArrayList<>(List.of(step))).build()));

        service.ingest(event("run-1", health));

        assertThat(legacy.getStatus()).isEqualTo(AuditFindingStatus.RESOLVED);
        assertThat(legacy.getResolvedAt()).isEqualTo(now);
        assertThat(legacy.getDispositionNote()).isEqualTo("Bekannt, Abbau laeuft\nRegel nicht mehr im Katalog");
        assertThat(savedFindings()).contains(legacy);
    }

    /** Run with a fact-sheet block: the evidence check applies (TODO a94b56a0). */
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
        // findingRepository.saveAll is stubbed in setUp; findBy... returns empty by Mockito default.
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

    /** A run with a freely selectable ruleKey — for the catalog check. */
    private void stubRunWithRuleKey(String runId, String ruleKey) {
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":[{\"ruleKey\":\""
                + ruleKey + "\",\"subject\":\"Big.java\",\"severity\":\"HIGH\",\"title\":\"t\","
                + "\"evidence\":\"e\"}]} -->\n# Report";
        StepResult step = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output(envelope)
                .metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        WorkflowRun run = WorkflowRun.builder().id(runId).workflow(Workflow.builder().name("Coverage").build())
                .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT).steps(new ArrayList<>(List.of(step))).build();
        when(runRepository.findByIdWithSteps(runId)).thenReturn(Optional.of(run));
        // HIGH produces a managed TODO — only in the valid case, hence lenient.
        lenient().when(todoRepository.save(any())).thenAnswer(inv -> {
            Todo todo = inv.getArgument(0);
            if (todo.getId() == null) todo.setId("todo-" + UUID.randomUUID());
            return todo;
        });
    }

    /**
     * Drifted ruleKeys carry the right <i>number</i> of findings and therefore pass the
     * counting gate block. The ingestion then created nothing but new identities and closed
     * the previous generation as RESOLVED — as many recurrences (TODO f57945e2).
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

    /** A key from the catalog passes through unchanged. */
    @Test
    void bekannterRuleKeyBleibtGueltig() {
        stubRunWithRuleKey("run-1", "test-coverage.untested-class");

        service.ingest(event("run-1", COVERAGE_KEY));

        ArgumentCaptor<AuditReport> report = ArgumentCaptor.forClass(AuditReport.class);
        verify(reportRepository).save(report.capture());
        assertThat(report.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.VALID);
        assertThat(savedFindings()).isNotEmpty();
    }

    /** An audit without a catalog entry assigns its keys freely — unchanged behavior. */
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
     * The seam to the {@link AuditSnapshotApplier} (audit TODO aac5fbf7): the service checks and
     * saves the report and passes on only a valid one — with the findings of the envelope and
     * the metrics of the run.
     */
    @Nested
    class HandOverToTheApplier {

        @Mock private AuditSnapshotApplier applier;
        private AuditIngestionService withMockedApplier;

        /** After the outer setUp: only then do the mocks the service receives exist. */
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
    /**
     * Provider and model of the report (fa66ea31): if a shell block assembles the envelope, the
     * reportBlock is not an AI step. The model is then named by the promptBlock's step, not the workflow.
     */
    @Nested
    class ProviderAndModel {

        private static final String ENVELOPE = "# Report\n<!-- mysuricates:audit-result:v1 "
                + "{\"snapshotComplete\":true,\"findings\":[]} -->";

        private WorkflowRunCompletedEvent eventWith(Map<String, Object> audit) {
            return new WorkflowRunCompletedEvent(this, "run-1", "wf-1", "Coverage", PROJECT, "/tmp/p",
                    RunStatus.COMPLETED, Map.of(), Map.of("audit", audit), null);
        }

        private void stubRun(Map<String, Object> coachMeta, Map<String, Object> reportMeta) {
            StepResult coach = StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED).output("Bericht")
                    .metadata(new HashMap<>(coachMeta)).build();
            StepResult report = StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED).output(ENVELOPE)
                    .metadata(new HashMap<>(reportMeta)).build();
            WorkflowRun run = WorkflowRun.builder().id("run-1")
                    .workflow(Workflow.builder().name("Coverage").provider("CODEX").model("gpt-workflow").build())
                    .status(RunStatus.COMPLETED).completedAt(ACCEPTED_AT)
                    .steps(new ArrayList<>(List.of(coach, report))).build();
            when(runRepository.findByIdWithSteps("run-1")).thenReturn(Optional.of(run));
        }

        private AuditReport ingested(Map<String, Object> audit) {
            service.ingest(eventWith(audit));
            ArgumentCaptor<AuditReport> stored = ArgumentCaptor.forClass(AuditReport.class);
            verify(reportRepository).save(stored.capture());
            assertThat(stored.getValue().getIngestionStatus()).isEqualTo(AuditIngestionStatus.VALID);
            return stored.getValue();
        }

        private Map<String, Object> audit(boolean withPromptBlock) {
            Map<String, Object> audit = new HashMap<>(Map.of(
                    "enabled", true, "key", AUDIT_KEY, "category", "quality", "reportBlock", "report"));
            if (withPromptBlock) audit.put("promptBlock", "coach");
            return audit;
        }

        @Test
        void promptBlock_namesProviderAndModel_whenTheReportBlockIsAShellStep() {
            stubRun(Map.of("blockName", "coach", "provider", "CLAUDE", "model", "sonnet"),
                    Map.of("blockName", "report"));

            AuditReport report = ingested(audit(true));

            assertThat(report.getProvider()).isEqualTo("CLAUDE");
            assertThat(report.getModel()).isEqualTo("sonnet");
        }

        @Test
        void promptStepWithoutValues_fallsBackToTheReportStep_thenToTheWorkflow() {
            stubRun(Map.of("blockName", "coach"), Map.of("blockName", "report", "provider", "PI"));

            AuditReport report = ingested(audit(true));

            assertThat(report.getProvider()).isEqualTo("PI");
            assertThat(report.getModel()).isEqualTo("gpt-workflow");
        }

        @Test
        void withoutPromptBlock_theReportStepCounts_asBefore() {
            stubRun(Map.of("blockName", "coach", "provider", "CLAUDE", "model", "sonnet"),
                    Map.of("blockName", "report"));

            AuditReport report = ingested(audit(false));

            assertThat(report.getProvider()).isEqualTo("CODEX");
            assertThat(report.getModel()).isEqualTo("gpt-workflow");
        }
    }
}
