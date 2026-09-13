package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
    private static final String AUDIT_KEY = "mysuricates.health";

    @Mock private WorkflowRunRepository runRepository;
    @Mock private AuditReportRepository reportRepository;
    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuditIngestionService service;

    @BeforeEach
    void setUp() {
        service = new AuditIngestionService(runRepository, reportRepository, findingRepository, occurrenceRepository,
                findingTodoRepository, todoRepository, new AuditResultParser(new ObjectMapper()),
                new AuditAcceptancePolicy(), eventPublisher);
        when(reportRepository.save(any())).thenAnswer(inv -> {
            AuditReport report = inv.getArgument(0);
            if (report.getId() == null) report.setId("rep-" + UUID.randomUUID());
            return report;
        });
        when(findingRepository.save(any())).thenAnswer(inv -> {
            AuditFinding finding = inv.getArgument(0);
            if (finding.getId() == null) finding.setId("f-" + UUID.randomUUID());
            return finding;
        });
    }

    private WorkflowRunCompletedEvent event(String runId) {
        Map<String, Object> workflowMetadata = Map.of("audit", Map.of(
                "enabled", true, "key", AUDIT_KEY, "category", "quality", "reportBlock", "report"));
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
        if (inSnapshot) {
            // Der echte Fingerprint ist ein SHA-256 ueber projectId/auditKey/ruleKey/subject — das Finding
            // uebernimmt den angefragten Wert, damit die Abwesenheits-Reconciliation es als gesehen zaehlt.
            when(findingRepository.findByProjectIdAndAuditKeyAndFingerprint(eq(PROJECT), eq(AUDIT_KEY), anyString()))
                    .thenAnswer(inv -> { finding.setFingerprint(inv.getArgument(2)); return Optional.of(finding); });
        } else {
            when(findingRepository.findByProjectIdAndAuditKeyAndFingerprint(eq(PROJECT), eq(AUDIT_KEY), anyString()))
                    .thenReturn(Optional.empty());
        }
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
}
