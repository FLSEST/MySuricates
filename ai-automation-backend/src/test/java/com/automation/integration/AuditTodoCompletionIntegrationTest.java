package com.automation.integration;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditIngestionStatus;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.TodoRepository;
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

/**
 * Follow-up finding to ab1ed481: Every way that sets a linked TODO to DONE and saves it — REST
 * ({@code TodoService}), TodoTrigger auto-close ({@code TodoFeedbackListener}), linked workflow
 * ({@code TodoResolutionListener}), bulk closing via saveAll —, brings the open finding to
 * AWAITING_VERIFICATION. The listeners do exactly this here: {@code setStatus(DONE)} and {@code save}.
 * The domain event of the save runs in the same transaction; if it rolls back, the finding stays too.
 */
@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class AuditTodoCompletionIntegrationTest {

    private static final Instant T1 = Instant.parse("2026-10-01T10:00:00Z");

    @Autowired private TodoRepository todoRepository;
    @Autowired private AuditFindingRepository findingRepository;
    @Autowired private AuditFindingTodoRepository findingTodoRepository;
    @Autowired private AuditReportRepository reportRepository;
    @Autowired private com.automation.service.todo.TodoService todoService;

    private String projectId;
    private String reportId;

    @BeforeEach
    void setUp() {
        projectId = "p-" + UUID.randomUUID();
        reportId = reportRepository.save(AuditReport.builder()
                .projectId(projectId).workflowId("wf-1").workflowRunId("run-" + UUID.randomUUID())
                .auditKey("test.completion").category("security").title("Security")
                .ingestionStatus(AuditIngestionStatus.VALID).generatedAt(T1).build()).getId();
    }

    private AuditFinding openFinding(String fingerprint) {
        return findingRepository.save(AuditFinding.builder()
                .projectId(projectId).auditKey("test.completion").fingerprint(fingerprint).ruleKey("rule")
                .subject(fingerprint).severity(AuditSeverity.HIGH).title("Befund")
                .firstSeenReportId(reportId).lastSeenReportId(reportId)
                .firstSeenRunId("run-1").lastSeenRunId("run-1").firstSeenAt(T1).lastSeenAt(T1).build());
    }

    private Todo linkedTodo(AuditFinding finding, boolean managed) {
        Todo todo = todoRepository.save(Todo.builder().title("Fix")
                .status(Todo.Status.IN_PROGRESS).build());
        findingTodoRepository.save(AuditFindingTodo.builder()
                .findingId(finding.getId()).todoId(todo.getId()).managed(managed).build());
        return todo;
    }

    private AuditFindingStatus statusOf(AuditFinding finding) {
        return findingRepository.findById(finding.getId()).orElseThrow().getStatus();
    }

    @Test
    void aListenerStyleSave_ofADoneTodo_movesTheFindingToAwaitingVerification() {
        AuditFinding managedFinding = openFinding("fp-managed");
        AuditFinding manualFinding = openFinding("fp-manual");
        Todo managed = linkedTodo(managedFinding, true);
        Todo manual = linkedTodo(manualFinding, false);

        managed.setStatus(Todo.Status.DONE);
        todoRepository.save(managed);
        manual.setStatus(Todo.Status.DONE);
        todoRepository.save(manual);

        assertThat(statusOf(managedFinding)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(statusOf(manualFinding)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
    }

    @Test
    void closingSeveralTodosWithSaveAll_movesEachFinding() {
        AuditFinding a = openFinding("fp-a");
        AuditFinding b = openFinding("fp-b");
        Todo todoA = linkedTodo(a, true);
        Todo todoB = linkedTodo(b, false);

        todoA.setStatus(Todo.Status.DONE);
        todoB.setStatus(Todo.Status.DONE);
        todoRepository.saveAll(List.of(todoA, todoB));

        assertThat(statusOf(a)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(statusOf(b)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
    }

    @Test
    void savingWithoutATransitionToDone_leavesTheFindingOpen() {
        AuditFinding finding = openFinding("fp-open");
        Todo todo = linkedTodo(finding, false);

        todo.setTitle("Fix, umbenannt");
        todoRepository.save(todo);

        assertThat(statusOf(finding)).isEqualTo(AuditFindingStatus.OPEN);
    }

    /** Review of ab1ed481: the bulk UPDATE loads no entities — the finding follows anyway. */
    @Test
    void bulkStatusUpdateToDone_movesTheFindings() {
        AuditFinding a = openFinding("fp-bulk-a");
        AuditFinding b = openFinding("fp-bulk-b");
        Todo todoA = linkedTodo(a, true);
        Todo todoB = linkedTodo(b, false);

        todoService.bulkUpdateStatus(List.of(todoA.getId(), todoB.getId()), Todo.Status.DONE);

        assertThat(statusOf(a)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
        assertThat(statusOf(b)).isEqualTo(AuditFindingStatus.AWAITING_VERIFICATION);
    }
}
