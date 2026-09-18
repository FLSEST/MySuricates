package com.automation.service.audit;

import com.automation.entity.AuditFinding;
import com.automation.entity.AuditFindingStatus;
import com.automation.entity.AuditFindingTodo;
import com.automation.entity.AuditSeverity;
import com.automation.entity.Todo;
import com.automation.repository.AuditFindingOccurrenceRepository;
import com.automation.repository.AuditFindingRepository;
import com.automation.repository.AuditFindingTodoRepository;
import com.automation.repository.AuditReportRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Die TODO-Verknuepfungen des Audit Centers: Loesen einer Verknuepfung laesst keinen Verweis zurueck. */
@ExtendWith(MockitoExtension.class)
class AuditCenterServiceTest {

    private static final String FINDING_ID = "f-1";
    private static final String TODO_ID = "t-1";

    @Mock private AuditReportRepository reportRepository;
    @Mock private AuditFindingRepository findingRepository;
    @Mock private AuditFindingOccurrenceRepository occurrenceRepository;
    @Mock private AuditFindingTodoRepository findingTodoRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ApplicationEventPublisher eventPublisher;

    private AuditCenterService service;

    @BeforeEach
    void setUp() {
        service = new AuditCenterService(reportRepository, findingRepository, occurrenceRepository,
                findingTodoRepository, todoRepository, new AuditAcceptancePolicy(), eventPublisher,
                new ManagedTodoReconciler(findingRepository, findingTodoRepository, todoRepository));
    }

    @Test
    void unlinkTodo_clearsTheReferenceBackToTheFinding() {
        AuditFinding finding = finding();
        Todo todo = Todo.builder().id(TODO_ID).title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId("p-1")
                .referenceType("audit_finding").referenceId(FINDING_ID).build();
        AuditFindingTodo link = AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(false).build();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.unlinkTodo(FINDING_ID, TODO_ID);

        verify(findingTodoRepository).delete(link);
        assertThat(todo.getReferenceId()).as("Deep-Link /reports?finding=… darf nicht stehen bleiben").isNull();
        assertThat(todo.getReferenceType()).isNull();
        verify(todoRepository).save(todo);
    }

    @Test
    void unlinkTodo_leavesAReferenceToAnotherFindingAlone() {
        AuditFinding finding = finding();
        Todo todo = Todo.builder().id(TODO_ID).title("Shell-Aufruf ohne Escaping")
                .status(Todo.Status.OPEN).priority(Todo.Priority.HIGH).projectId("p-1")
                .referenceType("audit_finding").referenceId("f-other").build();
        AuditFindingTodo link = AuditFindingTodo.builder().findingId(FINDING_ID).todoId(TODO_ID).managed(false).build();
        when(findingRepository.findById(FINDING_ID)).thenReturn(Optional.of(finding));
        when(findingTodoRepository.findByFindingIdAndTodoId(FINDING_ID, TODO_ID)).thenReturn(Optional.of(link));
        when(todoRepository.findById(TODO_ID)).thenReturn(Optional.of(todo));
        when(findingTodoRepository.findByFindingIdIn(List.of(FINDING_ID))).thenReturn(List.of());

        service.unlinkTodo(FINDING_ID, TODO_ID);

        assertThat(todo.getReferenceId()).isEqualTo("f-other");
    }

    private static AuditFinding finding() {
        return AuditFinding.builder().id(FINDING_ID).projectId("p-1").auditKey("mysuricates.security")
                .fingerprint("fp").ruleKey("shell.injection").subject("ContainerShellService")
                .severity(AuditSeverity.HIGH).title("Shell-Aufruf ohne Escaping")
                .status(AuditFindingStatus.OPEN)
                .firstSeenReportId("rep-1").lastSeenReportId("rep-1")
                .firstSeenRunId("run-1").lastSeenRunId("run-1")
                .firstSeenAt(Instant.parse("2026-09-05T10:30:00Z")).lastSeenAt(Instant.parse("2026-09-12T10:30:00Z"))
                .build();
    }
}
