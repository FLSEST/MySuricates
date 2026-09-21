package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditReport;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Das verwaltete TODO eines Findings: einmal anlegen, danach am Status der Findings ausrichten. */
@ExtendWith(MockitoExtension.class)
class ManagedTodoWriterTest {

    private static final String FINDING_ID = "f-1";

    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private ManagedTodoWriter writer() {
        return new ManagedTodoWriter(findingRepository, findingTodoRepository, todoRepository, eventPublisher);
    }

    @Test
    void ensure_reusesTheExistingManagedTodo_andIgnoresManualLinks() {
        Todo existing = todo("t-managed", Todo.Status.OPEN);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(
                link("t-manual", false), link("t-managed", true)));
        when(todoRepository.findById("t-managed")).thenReturn(Optional.of(existing));

        assertThat(writer().ensure(finding(FINDING_ID, AuditFindingStatus.OPEN), report())).isSameAs(existing);

        verify(todoRepository, never()).save(any());
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void ensure_createsTodoLinkAndEvent_fromFindingAndReport() {
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            t.setId("t-new");
            return t;
        });

        Todo created = writer().ensure(finding(FINDING_ID, AuditFindingStatus.OPEN), report());

        assertThat(created.getTitle()).isEqualTo("Shell-Aufruf ohne Escaping");
        assertThat(created.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(created.getPriority()).isEqualTo(Todo.Priority.CRITICAL);
        assertThat(created.getTags()).isEqualTo("[\"audit\",\"audit:mysuricates.security\",\"auto:workflow\"]");
        assertThat(created.getProjectId()).isEqualTo("p-1");
        assertThat(created.getSource()).isEqualTo(Todo.Source.WORKFLOW);
        assertThat(created.getSourceAgent()).isEqualTo(ManagedTodoWriter.SOURCE_AGENT);
        assertThat(created.getWorkflowRunId()).as("Run des Reports, nicht des Findings").isEqualTo("run-report");
        assertThat(created.getReferenceType()).isEqualTo(ManagedTodoWriter.REFERENCE_TYPE);
        assertThat(created.getReferenceId()).isEqualTo(FINDING_ID);
        assertThat(created.getDescription())
                .startsWith("Eingaben landen ungeprueft in der Shell.")
                .contains("Evidence: ContainerShellService.java:42")
                .contains("[Audit finding](/reports?finding=f-1)")
                .contains("[Evidence run](/workflows/wf-1/run/run-report)");

        ArgumentCaptor<AuditFindingTodo> savedLink = ArgumentCaptor.forClass(AuditFindingTodo.class);
        verify(findingTodoRepository).save(savedLink.capture());
        assertThat(savedLink.getValue().getFindingId()).isEqualTo(FINDING_ID);
        assertThat(savedLink.getValue().getTodoId()).isEqualTo("t-new");
        assertThat(savedLink.getValue().isManaged()).isTrue();
        ArgumentCaptor<TodoCreatedEvent> event = ArgumentCaptor.forClass(TodoCreatedEvent.class);
        verify(eventPublisher).publishEvent(event.capture());
        assertThat(event.getValue().getTodo()).isSameAs(created);
    }

    @Test
    void ensure_replacesADanglingManagedLink() {
        AuditFindingTodo dangling = link("t-gone", true);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of(dangling));
        when(todoRepository.findById("t-gone")).thenReturn(Optional.empty());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        writer().ensure(finding(FINDING_ID, AuditFindingStatus.OPEN), report());

        verify(findingTodoRepository).delete(dangling);
        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    void ensure_describesAFindingWithoutDescriptionOrEvidence() {
        AuditFinding finding = finding(FINDING_ID, AuditFindingStatus.OPEN);
        finding.setDescription(null);
        finding.setEvidence(null);
        when(findingTodoRepository.findByFindingId(FINDING_ID)).thenReturn(List.of());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo created = writer().ensure(finding, report());

        assertThat(created.getDescription())
                .startsWith("Audit finding requires remediation.")
                .contains("Evidence: not provided");
    }

    @Test
    void reconcile_withoutTouchedFindings_doesNothing() {
        writer().reconcile(List.of());

        verifyNoInteractions(findingTodoRepository, todoRepository, findingRepository);
    }

    @Test
    void reconcile_closesTheTodoOnceEveryLinkedFindingIsTerminal() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        AuditFinding dismissed = finding("f-2", AuditFindingStatus.DISMISSED);
        Todo todo = todo("t-1", Todo.Status.IN_PROGRESS);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByTodoId("t-1")).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").build(),
                AuditFindingTodo.builder().findingId("f-2").todoId("t-1").build()));
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(resolved));
        when(findingRepository.findById("f-2")).thenReturn(Optional.of(dismissed));

        writer().reconcile(List.of(resolved, resolved));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(todo);
    }

    @Test
    void reconcile_keepsTheTodoOpenWhileOneLinkedFindingIsOpen() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        AuditFinding open = finding("f-2", AuditFindingStatus.OPEN);
        Todo todo = todo("t-1", Todo.Status.OPEN);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByTodoId("t-1")).thenReturn(List.of(
                AuditFindingTodo.builder().findingId(FINDING_ID).todoId("t-1").build(),
                AuditFindingTodo.builder().findingId("f-2").todoId("t-1").build()));
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(resolved));
        when(findingRepository.findById("f-2")).thenReturn(Optional.of(open));

        writer().reconcile(List.of(resolved));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        verify(todoRepository, never()).save(any());
    }

    @Test
    void reconcile_reopensADoneTodoWhenAFindingComesBack_andPointsItAtTheNewRun() {
        AuditFinding back = finding(FINDING_ID, AuditFindingStatus.OPEN);
        back.setLastSeenRunId("run-back");
        Todo todo = todo("t-1", Todo.Status.DONE);
        todo.setWorkflowRunId("run-old");
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of(link("t-1", true)));
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByTodoId("t-1")).thenReturn(List.of(link("t-1", true)));
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(back));

        writer().reconcile(List.of(back));

        assertThat(todo.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(todo.getWorkflowRunId()).isEqualTo("run-back");
        verify(todoRepository).save(todo);
    }

    @Test
    void reconcile_skipsManualLinksAndVanishedTodos() {
        AuditFinding resolved = finding(FINDING_ID, AuditFindingStatus.RESOLVED);
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID)))
                .thenReturn(List.of(link("t-manual", false), link("t-gone", true)));
        when(todoRepository.findById("t-gone")).thenReturn(Optional.empty());

        writer().reconcile(List.of(resolved));

        verify(todoRepository, never()).findById("t-manual");
        verify(todoRepository, never()).save(any());
    }

    private static AuditFindingTodo link(String todoId, boolean managed) {
        return AuditFindingTodo.builder().findingId(FINDING_ID).todoId(todoId).managed(managed).build();
    }

    private static Todo todo(String id, Todo.Status status) {
        return Todo.builder().id(id).title("Shell-Aufruf ohne Escaping").status(status)
                .priority(Todo.Priority.HIGH).projectId("p-1").build();
    }

    private static AuditReport report() {
        return AuditReport.builder().id("rep-1").projectId("p-1").workflowId("wf-1").workflowRunId("run-report")
                .auditKey("mysuricates.security").build();
    }

    private static AuditFinding finding(String id, AuditFindingStatus status) {
        return AuditFinding.builder().id(id).projectId("p-1").auditKey("mysuricates.security")
                .fingerprint("fp-" + id).ruleKey("shell.injection").subject("ContainerShellService")
                .severity(AuditSeverity.CRITICAL).title("Shell-Aufruf ohne Escaping")
                .description("Eingaben landen ungeprueft in der Shell.").evidence("ContainerShellService.java:42")
                .status(status).firstSeenReportId("rep-1").lastSeenReportId("rep-1")
                .firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}
