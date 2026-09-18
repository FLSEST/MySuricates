package com.automation.service.audit;

import com.automation.entity.*;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Der einmalige Merge-Job fuehrt Wiedergaenger zusammen, die vor der subject-Normalisierung als
 * eigene Findings entstanden sind (Audit-TODO 0e19648d) — und ist beim zweiten Lauf ein No-op.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFingerprintMigrationJobTest {

    private static final String AUDIT_KEY = "mysuricates.security";
    private static final String RULE_KEY = "shell.injection";
    private static final Instant T0 = Instant.parse("2026-09-05T10:30:00Z");
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");
    private static final Instant T2 = Instant.parse("2026-09-12T19:16:00Z");

    @Autowired private AuditFingerprintMigrationJob job;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingTodoRepository findingTodoRepository;
    @Autowired private AuditFindingOccurrenceRepository occurrenceRepository;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private ProjectRepository projectRepository;
    @Autowired private EntityManager entityManager;

    private String projectId;
    private String reportOld;
    private String reportNew;

    @BeforeEach
    void setUp() {
        projectId = projectRepository.save(Project.builder()
                .name("Audit Merge Test").type(ProjectType.CODE)
                .path("/tmp/audit-merge-test").status(ProjectStatus.RUNNING).build()).getId();
        reportOld = saveReport("run-old", T1);
        reportNew = saveReport("run-new", T2);
    }

    @Test
    void mergesDriftedTwinsOntoTheOldestFindingAndIsIdempotent() {
        AuditFinding older = saveFinding("ContainerShellService", AuditFindingStatus.RESOLVED, 3, T0, T1, reportOld, "run-old");
        AuditFinding newer = saveFinding("ContainerShellService.execute", AuditFindingStatus.OPEN, 1, T2, T2, reportNew, "run-new");
        AuditFinding untouched = saveFinding("application.yml:key-store-password", AuditFindingStatus.OPEN, 2, T1, T1, reportOld, "run-old");
        saveOccurrence(older.getId(), reportOld);
        saveOccurrence(newer.getId(), reportNew);
        saveOccurrence(newer.getId(), reportOld); // Kollision: das Ziel hat diesen Report schon
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(newer.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(newer.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        List<AuditFinding> remaining = findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY);
        assertThat(remaining).extracting(AuditFinding::getId)
                .containsExactlyInAnyOrder(older.getId(), untouched.getId());
        AuditFinding merged = findingRepository.findById(older.getId()).orElseThrow();
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
        assertThat(merged.getLastSeenAt()).isEqualTo(T2);
        assertThat(merged.getLastSeenReportId()).isEqualTo(reportNew);
        assertThat(merged.getSubject()).as("neuester subject gewinnt").isEqualTo("ContainerShellService.execute");
        assertThat(merged.getStatus()).as("ein offener Wiedergaenger haelt das Ziel offen")
                .isEqualTo(AuditFindingStatus.OPEN);
        assertThat(merged.getResolvedAt()).isNull();
        assertThat(merged.getFingerprint())
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "ContainerShellService"));
        assertThat(findingRepository.findById(untouched.getId()).orElseThrow().getFingerprint())
                .as("Einzelgaenger werden nur neu gehasht, nicht veraendert")
                .isEqualTo(AuditIngestionService.fingerprint(projectId, AUDIT_KEY, RULE_KEY, "application.yml"));
        assertThat(findingTodoRepository.findByTodoId(todoId)).extracting(AuditFindingTodo::getFindingId)
                .containsExactly(older.getId());
        assertThat(todoRepository.findById(todoId)).get().extracting(Todo::getStatus).isEqualTo(Todo.Status.OPEN);
        assertThat(occurrenceRepository.findByFindingId(older.getId()))
                .extracting(AuditFindingOccurrence::getReportId)
                .containsExactlyInAnyOrder(reportOld, reportNew);

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY)).hasSize(2);
        AuditFinding afterSecondRun = findingRepository.findById(older.getId()).orElseThrow();
        assertThat(afterSecondRun.getOccurrenceCount()).as("zweiter Lauf aendert nichts").isEqualTo(4);
        assertThat(afterSecondRun.getFingerprint()).isEqualTo(merged.getFingerprint());
        assertThat(findingTodoRepository.findByTodoId(todoId)).hasSize(1);
    }

    /**
     * Ein akzeptiertes Risiko ist eine menschliche Entscheidung — ein Wiedergaenger darf sie beim
     * Zusammenfuehren nicht aufheben. Wiedereroeffnung nur ueber {@link AuditAcceptancePolicy}.
     */
    @Test
    void acceptedRiskTargetStaysAcceptedWhenTheTwinIsOpen() {
        AuditFinding accepted = saveFinding("ContainerShellService", AuditFindingStatus.ACCEPTED_RISK, 3, T0, T1, reportOld, "run-old");
        accepted.setDispositionNote("Bewusst so, Aufrufer sind validiert");
        accepted.setDispositionEvidence("ContainerShellService: 3 Fundstellen");
        accepted.setDispositionAt(T1);
        accepted.setResolvedAt(T1);
        AuditFinding twin = saveFinding("ContainerShellService.execute", AuditFindingStatus.OPEN, 1, T2, T2, reportNew, "run-new");
        String todoId = todoRepository.save(Todo.builder().title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId(projectId)
                .source(Todo.Source.WORKFLOW).sourceAgent("audit-center")
                .referenceType("audit_finding").referenceId(twin.getId()).build()).getId();
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(twin.getId()).todoId(todoId).managed(true).build());

        job.migrate();
        entityManager.flush();
        entityManager.clear();

        assertThat(findingRepository.findByProjectIdAndAuditKey(projectId, AUDIT_KEY))
                .extracting(AuditFinding::getId).containsExactly(accepted.getId());
        AuditFinding merged = findingRepository.findById(accepted.getId()).orElseThrow();
        assertThat(merged.getStatus()).as("Akzeptanz bleibt, der Wiedergaenger hebt sie nicht auf")
                .isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(merged.getDispositionNote()).isEqualTo("Bewusst so, Aufrufer sind validiert");
        assertThat(merged.getDispositionEvidence()).isEqualTo("ContainerShellService: 3 Fundstellen");
        assertThat(merged.getDispositionAt()).isEqualTo(T1);
        assertThat(merged.getResolvedAt()).isEqualTo(T1);
        assertThat(merged.getOccurrenceCount()).isEqualTo(4);
        assertThat(merged.getLastSeenAt()).isEqualTo(T2);
        assertThat(findingTodoRepository.findByTodoId(todoId)).extracting(AuditFindingTodo::getFindingId)
                .containsExactly(accepted.getId());
    }

    private String saveReport(String runId, Instant generatedAt) {
        return reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId(runId).auditKey(AUDIT_KEY)
                .category("security").title("Security Audit").ingestionStatus(AuditIngestionStatus.VALID)
                .generatedAt(generatedAt).build()).getId();
    }

    private AuditFinding saveFinding(String subject, AuditFindingStatus status, int occurrences,
                                     Instant firstSeenAt, Instant lastSeenAt, String reportId, String runId) {
        AuditFinding saved = findingRepository.saveAndFlush(AuditFinding.builder()
                .projectId(projectId).auditKey(AUDIT_KEY).fingerprint(legacyFingerprint(subject))
                .ruleKey(RULE_KEY).subject(subject).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").evidence(subject + ": 3 Fundstellen").status(status)
                .resolvedAt(status == AuditFindingStatus.RESOLVED ? lastSeenAt : null)
                .firstSeenReportId(reportId).lastSeenReportId(reportId)
                .firstSeenRunId(runId).lastSeenRunId(runId)
                .firstSeenAt(firstSeenAt).lastSeenAt(lastSeenAt).occurrenceCount(occurrences).build());
        return saved;
    }

    private void saveOccurrence(String findingId, String reportId) {
        occurrenceRepository.saveAndFlush(AuditFindingOccurrence.builder()
                .findingId(findingId).reportId(reportId).severity(AuditSeverity.HIGH)
                .title("Shell-Aufruf ohne Escaping").observedAt(T1).build());
    }

    /** Fingerprint vor der Normalisierung: SHA-256 ueber das rohe subject. */
    private String legacyFingerprint(String subject) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256").digest(
                    String.join("\n", projectId, AUDIT_KEY, RULE_KEY, subject)
                            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
