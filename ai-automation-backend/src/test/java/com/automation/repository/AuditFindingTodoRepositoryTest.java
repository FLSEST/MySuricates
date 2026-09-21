package com.automation.repository;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Project;
import com.automation.entity.ProjectStatus;
import com.automation.entity.ProjectType;
import com.automation.entity.Todo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditFindingTodoRepositoryTest {

    private static final String AUDIT_KEY = "test.finding-todo-repo";
    private static final Instant T1 = Instant.parse("2026-09-12T10:30:00Z");

    @Autowired private AuditFindingTodoRepository findingTodoRepository;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private TodoRepository todoRepository;
    @Autowired private ProjectRepository projectRepository;

    private String projectId;
    private String reportId;

    @BeforeEach
    void setUp() {
        projectId = projectRepository.save(Project.builder()
                .name("Audit Todo Repo Test").type(ProjectType.CODE)
                .path("/tmp/audit-todo-repo-test").status(ProjectStatus.STOPPED).build()).getId();
        reportId = reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey(AUDIT_KEY).category("security").title("Security")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(T1).build()).getId();
    }

    private String saveFinding(String fingerprint) {
        return findingRepository.save(AuditFinding.builder()
                .projectId(projectId).auditKey(AUDIT_KEY).fingerprint(fingerprint).ruleKey("shell.injection")
                .subject("ContainerShellService").severity(AuditSeverity.HIGH).title("Shell-Aufruf")
                .firstSeenReportId(reportId).lastSeenReportId(reportId)
                .firstSeenRunId("run-1").lastSeenRunId("run-1").firstSeenAt(T1).lastSeenAt(T1).build()).getId();
    }

    private String saveTodo(String title) {
        return todoRepository.save(Todo.builder().title(title).status(Todo.Status.OPEN)
                .priority(Todo.Priority.HIGH).projectId(projectId).source(Todo.Source.WORKFLOW).build()).getId();
    }

    private AuditFindingTodo link(String findingId, String todoId, boolean managed) {
        return findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(findingId).todoId(todoId).managed(managed).build());
    }

    @Test
    void findByFindingId_returnsManagedAndManualLinks() {
        String finding = saveFinding("fp-1");
        String managed = saveTodo("verwaltet");
        String manual = saveTodo("manuell");
        link(finding, managed, true);
        link(finding, manual, false);

        assertThat(findingTodoRepository.findByFindingId(finding))
                .extracting(AuditFindingTodo::getTodoId, AuditFindingTodo::isManaged)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(managed, true),
                        org.assertj.core.groups.Tuple.tuple(manual, false));
    }

    @Test
    void findByTodoId_returnsEveryFindingOfOneTodo() {
        String a = saveFinding("fp-a");
        String b = saveFinding("fp-b");
        String todo = saveTodo("gemeinsam");
        link(a, todo, true);
        link(b, todo, false);

        assertThat(findingTodoRepository.findByTodoId(todo))
                .extracting(AuditFindingTodo::getFindingId).containsExactlyInAnyOrder(a, b);
    }

    @Test
    void findByFindingIdIn_batchesSeveralFindings() {
        String a = saveFinding("fp-a");
        String b = saveFinding("fp-b");
        String c = saveFinding("fp-c");
        link(a, saveTodo("a"), true);
        link(b, saveTodo("b"), true);
        link(c, saveTodo("c"), true);

        assertThat(findingTodoRepository.findByFindingIdIn(List.of(a, b)))
                .extracting(AuditFindingTodo::getFindingId).containsExactlyInAnyOrder(a, b);
        assertThat(findingTodoRepository.findByFindingIdIn(List.of())).isEmpty();
    }

    @Test
    void findByFindingIdAndTodoId_matchesOnlyTheExactPair() {
        String finding = saveFinding("fp-1");
        String todo = saveTodo("t");
        String other = saveTodo("other");
        AuditFindingTodo saved = link(finding, todo, false);

        assertThat(findingTodoRepository.findByFindingIdAndTodoId(finding, todo))
                .get().extracting(AuditFindingTodo::getId).isEqualTo(saved.getId());
        assertThat(findingTodoRepository.findByFindingIdAndTodoId(finding, other)).isEmpty();
    }
}
