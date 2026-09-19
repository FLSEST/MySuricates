package com.automation.integration;

import com.automation.entity.*;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.audit.AuditIngestionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Beweist gegen echtes SQLite, dass subject-Drift zwischen zwei Audit-Runs kein zweites Finding
 * mehr erzeugt: der Fingerprint haengt am normalisierten subject (Audit-TODO 0e19648d). Vorher
 * wurde das alte Finding RESOLVED, ein neues OPEN angelegt und das managed TODO faelschlich DONE.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFingerprintDriftIntegrationTest {

    private static final String AUDIT_KEY = "mysuricates.security";

    @Autowired private AuditIngestionService ingestionService;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingTodoRepository findingTodoRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private WorkflowRunRepository runRepository;
    @Autowired private WorkflowRepository workflowRepository;
    @Autowired private ProjectRepository projectRepository;

    private Project project;
    private Workflow workflow;

    @BeforeEach
    void setUp() {
        project = projectRepository.save(Project.builder()
                .name("Audit Drift Test").type(ProjectType.CODE)
                .path("/tmp/audit-drift-test").status(ProjectStatus.RUNNING).build());
        workflow = workflowRepository.save(Workflow.builder()
                .name("Security Audit").project(project).build());
    }

    @Test
    void driftingSubjectSuffixKeepsOneFindingAndItsManagedTodoOpen() {
        ingest("ContainerShellService", Instant.parse("2026-09-12T10:30:00Z"));

        List<AuditFinding> afterFirst = findingRepository.findByProjectIdAndAuditKey(project.getId(), AUDIT_KEY);
        assertThat(afterFirst).hasSize(1);
        String findingId = afterFirst.get(0).getId();
        List<AuditFindingTodo> links = findingTodoRepository.findByFindingId(findingId);
        assertThat(links).hasSize(1);
        String todoId = links.get(0).getTodoId();

        ingest("ContainerShellService.execute", Instant.parse("2026-09-12T19:16:00Z"));

        List<AuditFinding> afterSecond = findingRepository.findByProjectIdAndAuditKey(project.getId(), AUDIT_KEY);
        assertThat(afterSecond).as("subject-Drift darf kein zweites Finding erzeugen").hasSize(1);
        AuditFinding finding = afterSecond.get(0);
        assertThat(finding.getId()).isEqualTo(findingId);
        assertThat(finding.getStatus()).isEqualTo(AuditFindingStatus.OPEN);
        assertThat(finding.getOccurrenceCount()).isEqualTo(2);
        assertThat(finding.getSubject()).as("neuester subject gewinnt als Anzeigeattribut")
                .isEqualTo("ContainerShellService.execute");
        assertThat(todoRepository.findById(todoId)).get()
                .extracting(Todo::getStatus).isEqualTo(Todo.Status.OPEN);
        assertThat(findingTodoRepository.findByTodoId(todoId)).hasSize(1);
    }

    /**
     * Vor dem Fix erzeugte ein Drift-Subject ein neues OPEN-Finding samt managed TODO und unterlief
     * damit die Risiko-Akzeptanz. Jetzt trifft die Ingestion das akzeptierte Finding — und die
     * Akzeptanz-Policy entscheidet allein (Wiedereroeffnung nur bei belegter Verschlechterung).
     */
    @Test
    void driftingSubjectDoesNotUndoAnAcceptedRisk() {
        ingest("ContainerShellService", Instant.parse("2026-09-12T10:30:00Z"));
        AuditFinding accepted = findingRepository.findByProjectIdAndAuditKey(project.getId(), AUDIT_KEY).get(0);
        accepted.setStatus(AuditFindingStatus.ACCEPTED_RISK);
        accepted.setDispositionNote("Bewusst so, Aufrufer sind validiert");
        accepted.setDispositionEvidence(accepted.getEvidence());
        accepted.setDispositionAt(Instant.parse("2026-09-12T11:00:00Z"));
        accepted.setResolvedAt(Instant.parse("2026-09-12T11:00:00Z"));
        findingRepository.saveAndFlush(accepted);
        long todosBefore = todoRepository.count();

        ingest("ContainerShellService.execute", Instant.parse("2026-09-12T19:16:00Z"));

        List<AuditFinding> findings = findingRepository.findByProjectIdAndAuditKey(project.getId(), AUDIT_KEY);
        assertThat(findings).hasSize(1);
        assertThat(findings.get(0).getStatus()).as("ohne belegte Verschlechterung bleibt die Akzeptanz")
                .isEqualTo(AuditFindingStatus.ACCEPTED_RISK);
        assertThat(findings.get(0).getDispositionNote()).isEqualTo("Bewusst so, Aufrufer sind validiert");
        assertThat(findings.get(0).getOccurrenceCount()).isEqualTo(2);
        assertThat(todoRepository.count()).as("kein neues managed TODO").isEqualTo(todosBefore);
    }

    private void ingest(String subject, Instant completedAt) {
        String envelope = "<!-- mysuricates:audit-result:v1 {\"snapshotComplete\":true,\"findings\":["
                + "{\"ruleKey\":\"sec.injection.shell\",\"subject\":\"" + subject + "\",\"severity\":\"HIGH\","
                + "\"title\":\"Shell-Aufruf ohne Escaping\",\"evidence\":\"" + subject + ": 3 Fundstellen\"}"
                + "]} -->\n# Security Report";
        WorkflowRun run = WorkflowRun.builder().workflow(workflow).workflowName(workflow.getName())
                .status(RunStatus.COMPLETED).startedAt(completedAt.minus(Duration.ofMinutes(5)))
                .completedAt(completedAt).steps(new ArrayList<>()).build();
        StepResult step = StepResult.builder().workflowRun(run).stepIndex(0).status(StepStatus.COMPLETED)
                .output(envelope).metadata(new HashMap<>(Map.of("blockName", "report"))).build();
        run.getSteps().add(step);
        String runId = runRepository.save(run).getId();

        Map<String, Object> workflowMetadata = Map.of("audit", Map.of(
                "enabled", true, "key", AUDIT_KEY, "category", "security", "reportBlock", "report"));
        ingestionService.ingest(new WorkflowRunCompletedEvent(this, runId, workflow.getId(), workflow.getName(),
                project.getId(), project.getPath(), RunStatus.COMPLETED,
                Map.of("gate_status", "RED"), workflowMetadata, null));
    }
}
