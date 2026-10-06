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
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reconciliation closes a managed TODO when every linked finding is terminal — and
 * only reopens what it closed itself.
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
        verify(todoRepository).saveAll(List.of(todo));
    }

    @Test
    void reopensOnlyWhatItClosedItself() {
        Todo todo = todo(Todo.Status.DONE);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
    }

    /** Someone is working on it — a reopened finding must not reset it to OPEN. */
    @Test
    void leavesAnInProgressTodoAlone() {
        Todo todo = todo(Todo.Status.IN_PROGRESS);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    /** Archived is a human decision and stays one. */
    @Test
    void leavesAnArchivedTodoAlone() {
        Todo todo = todo(Todo.Status.ARCHIVED);
        stub(todo, AuditFindingStatus.OPEN);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.ARCHIVED);
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    /** An archived TODO, by contrast, is closed when everything is terminal — that is not a step backwards. */
    @Test
    void stillClosesAnArchivedTodoWhenEverythingIsTerminal() {
        Todo todo = todo(Todo.Status.ARCHIVED);
        stub(todo, AuditFindingStatus.RESOLVED);

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
    }

    @Test
    void ignoresManuallyLinkedTodos() {
        AuditFindingTodo link = link(FINDING_ID, TODO_ID, false);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    /**
     * 8c791a45: one query per stage, no matter how many links are reconciled – before, per
     * managed TODO a findById and a findByTodoId, plus per link a findById of the finding.
     */
    @Test
    void loadsTodosLinksAndFindingsInOneQueryEach() {
        AuditFindingTodo closing = link("f-a", "t-a", true);
        AuditFindingTodo closingSecond = link("f-a2", "t-a", false);
        AuditFindingTodo reopening = link("f-b", "t-b", true);
        AuditFindingTodo manual = link("f-c", "t-c", false);
        Todo todoA = todo("t-a", Todo.Status.OPEN);
        Todo todoB = todo("t-b", Todo.Status.DONE);
        when(todoRepository.findAllById(List.of("t-a", "t-b"))).thenReturn(List.of(todoA, todoB));
        when(findingTodoRepository.findByTodoIdIn(Set.of("t-a", "t-b")))
                .thenReturn(List.of(closing, closingSecond, reopening));
        when(findingRepository.findAllById(any())).thenReturn(List.of(
                finding("f-a", AuditFindingStatus.RESOLVED), finding("f-a2", AuditFindingStatus.DISMISSED),
                finding("f-b", AuditFindingStatus.OPEN)));

        assertThat(reconciler().reconcile(List.of(closing, reopening, manual))).isEqualTo(2);

        assertThat(todoA.getStatus()).as("beide Findings terminal").isEqualTo(Todo.Status.DONE);
        assertThat(todoB.getStatus()).as("Finding wieder offen").isEqualTo(Todo.Status.OPEN);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Iterable<String>> findingIds = ArgumentCaptor.forClass(Iterable.class);
        verify(findingRepository, times(1)).findAllById(findingIds.capture());
        assertThat(findingIds.getValue()).containsExactlyInAnyOrder("f-a", "f-a2", "f-b");
        verify(todoRepository, times(1)).findAllById(any());
        verify(findingTodoRepository, times(1)).findByTodoIdIn(any());
        verify(todoRepository, never()).findById(any());
        verify(findingTodoRepository, never()).findByTodoId(any());
        verify(findingRepository, never()).findById(any());
        // e41531e0: both changed TODOs in one saveAll, no save per TODO.
        verify(todoRepository, times(1)).saveAll(List.of(todoA, todoB));
        verify(todoRepository, never()).save(any());
    }

    /** A managed TODO that no longer exists is skipped – without a further query. */
    @Test
    void skipsAManagedLinkWhoseTodoIsGone() {
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link(FINDING_ID, TODO_ID, true)));
        when(todoRepository.findAllById(List.of(TODO_ID))).thenReturn(List.of());

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
        verify(findingTodoRepository, never()).findByTodoIdIn(any());
        verify(findingRepository, never()).findAllById(any());
    }

    /** A deleted finding does not count: if the rest are terminal, it is closed. */
    @Test
    void ignoresADeletedFinding_andClosesWhenTheRestIsTerminal() {
        Todo todo = todo(Todo.Status.OPEN);
        AuditFindingTodo link = link(FINDING_ID, TODO_ID, true);
        AuditFindingTodo deleted = link("f-deleted", TODO_ID, true);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));
        when(todoRepository.findAllById(List.of(TODO_ID))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of(TODO_ID))).thenReturn(List.of(link, deleted));
        when(findingRepository.findAllById(List.of(FINDING_ID, "f-deleted")))
                .thenReturn(List.of(finding(AuditFindingStatus.RESOLVED)));

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isEqualTo(1);

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).saveAll(List.of(todo));
    }

    /** The same with a still open finding next to it: the deleted one does not make the TODO done. */
    @Test
    void aDeletedFindingDoesNotCloseATodoWithAnOpenOne() {
        Todo todo = todo(Todo.Status.OPEN);
        AuditFindingTodo link = link(FINDING_ID, TODO_ID, true);
        AuditFindingTodo deleted = link("f-deleted", TODO_ID, true);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));
        when(todoRepository.findAllById(List.of(TODO_ID))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of(TODO_ID))).thenReturn(List.of(link, deleted));
        when(findingRepository.findAllById(List.of(FINDING_ID, "f-deleted")))
                .thenReturn(List.of(finding(AuditFindingStatus.OPEN)));

        assertThat(reconciler().reconcileFinding(FINDING_ID)).isZero();

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(todoRepository, never()).save(any());
        verify(todoRepository, never()).saveAll(any());
    }

    private void stub(Todo todo, AuditFindingStatus status) {
        AuditFindingTodo link = link(FINDING_ID, TODO_ID, true);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(link));
        when(todoRepository.findAllById(List.of(TODO_ID))).thenReturn(List.of(todo));
        when(findingTodoRepository.findByTodoIdIn(Set.of(TODO_ID))).thenReturn(List.of(link));
        when(findingRepository.findAllById(List.of(FINDING_ID))).thenReturn(List.of(finding(status)));
    }

    private static AuditFindingTodo link(String findingId, String todoId, boolean managed) {
        return AuditFindingTodo.builder().findingId(findingId).todoId(todoId).managed(managed).build();
    }

    private static Todo todo(Todo.Status status) {
        return todo(TODO_ID, status);
    }

    private static Todo todo(String id, Todo.Status status) {
        return Todo.builder().id(id).title("Klasse ohne Test").status(status)
                .priority(Todo.Priority.HIGH).projectId("p-1").build();
    }

    private static AuditFinding finding(AuditFindingStatus status) {
        return finding(FINDING_ID, status);
    }

    private static AuditFinding finding(String id, AuditFindingStatus status) {
        return AuditFinding.builder().id(id).projectId("p-1").auditKey("mysuricates.test-coverage")
                .fingerprint("fp").ruleKey("test-coverage.untested-class").subject("RunAdmission.java")
                .severity(AuditSeverity.HIGH).title("Klasse ohne Test").status(status)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1").firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}
