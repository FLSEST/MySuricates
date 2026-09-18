package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Der Abgleich schliesst ein verwaltetes TODO, wenn jedes verknuepfte Finding terminal ist — und
 * oeffnet nur wieder, was er selbst geschlossen hat.
 */
@ExtendWith(MockitoExtension.class)
class ManagedTodoReconcilerTest {

    private static final String FINDING_ID = "f-1";
    private static final String TODO_ID = "t-1";

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;

    private ManagedTodoReconciler reconciler() {
        return new ManagedTodoReconciler(findingRepository, findingTodoRepository, todoRepository);
    }

    @Test
    void closesTheTodoWhenEveryLinkedFindingIsTerminal() {
        Todo todo = todo(Todo.Status.OPEN);
        stub(todo, AuditFindingStatus.ACCEPTED_RISK);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(todo);
    }

    @Test
    void reopensOnlyWhatItClosedItself() {
        Todo todo = todo(Todo.Status.DONE);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
    }

    /** Jemand arbeitet daran — ein wieder offenes Finding darf das nicht auf OPEN zuruecksetzen. */
    @Test
    void leavesAnInProgressTodoAlone() {
        Todo todo = todo(Todo.Status.IN_PROGRESS);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        verify(todoRepository, never()).save(any());
    }

    /** Archiviert ist eine menschliche Entscheidung und bleibt sie. */
    @Test
    void leavesAnArchivedTodoAlone() {
        Todo todo = todo(Todo.Status.ARCHIVED);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        verify(todoRepository, never()).save(any());
    }

    /** Ein archiviertes TODO wird dagegen geschlossen, wenn alles terminal ist — das ist kein Rueckschritt. */
    @Test
    void stillClosesAnArchivedTodoWhenEverythingIsTerminal() {
        Todo todo = todo(Todo.Status.ARCHIVED);
        stub(todo, AuditFindingStatus.RESOLVED);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
    }

    @Test
    void ignoresManuallyLinkedTodos() {
        AuditFindingTodo link = AuditFindingTodo.builder()
                .findingId(FINDING_ID).todoId(TODO_ID).managed(false).build();
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        verify(todoRepository, never()).save(any());
    }

    private void stub(Todo todo, AuditFindingStatus status) {
        AuditFindingTodo link = AuditFindingTodo.builder()
                .findingId(FINDING_ID).todoId(TODO_ID).managed(true).build();
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByTodoId(TODO_ID)).thenReturn(List.of(link));
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding(status)));
    }

    private static Todo todo(Todo.Status status) {
        return Todo.builder().id(TODO_ID).title("Klasse ohne Test").status(status)
                .priority(Todo.Priority.HIGH).projectId("p-1").build();
    }

    private static AuditFinding finding(AuditFindingStatus status) {
        return AuditFinding.builder().id(FINDING_ID).projectId("p-1").auditKey("mysuricates.test-coverage")
                .fingerprint("fp").ruleKey("test-coverage.untested-class").subject("RunAdmission.java")
                .severity(AuditSeverity.HIGH).title("Klasse ohne Test").status(status)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1").firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}
