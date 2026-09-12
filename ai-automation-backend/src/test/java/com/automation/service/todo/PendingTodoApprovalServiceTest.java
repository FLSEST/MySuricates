package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Approval mode of the auto-todo pipeline. The first test moved from {@code AutoTodoServiceTest};
 * the rest locks the reject path and the pending-map contract (Audit-TODO 9b4845ab).
 */
@ExtendWith(MockitoExtension.class)
class PendingTodoApprovalServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private StepResultRepository stepResultRepository;

    private PendingTodoApprovalService approvalService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        approvalService = new PendingTodoApprovalService(todoRepository, workflowRunRepository, transactionManager,
                eventPublisher, new AutoTodoTextBuilder(stepResultRepository));
    }

    private static WorkflowRun runWithPending(Map<String, Object> pending) {
        Workflow workflow = new Workflow();
        workflow.setId("wf-1");
        WorkflowRun run = new WorkflowRun();
        run.setId("run-app");
        run.setWorkflow(workflow);
        Map<String, Object> meta = new HashMap<>();
        meta.put("pending_todos", List.of(pending));
        run.setMetadata(meta);
        return run;
    }

    private static Map<String, Object> pending(String priority, String category) {
        Map<String, Object> pending = new HashMap<>();
        pending.put("title", "Approved Test Todo");
        pending.put("priority", priority);
        pending.put("description", "from pending");
        pending.put("projectId", "proj-1");
        if (category != null) pending.put("category", category);
        pending.put("tags", "[]");
        return pending;
    }

    @Test
    void approvePendingTodos_approved_createsTodosFromPendingMap() {
        WorkflowRun run = runWithPending(pending("HIGH", "general"));
        Map<String, Object> meta = run.getMetadata();

        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));
        when(todoRepository.findOpenGateByCompositeKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            t.setId("todo-new");
            return t;
        });
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> inv.getArgument(0));

        approvalService.approvePendingTodos("run-app", true);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Approved Test Todo");
        assertThat(captor.getValue().getSource()).isEqualTo(Todo.Source.WORKFLOW);
        assertThat(captor.getValue().getSourceAgent()).isEqualTo("auto-todo");
        assertThat(captor.getValue().getReferenceType()).isEqualTo("workflow_run");
        assertThat(captor.getValue().getReferenceId()).isEqualTo("wf-1");
        assertThat(captor.getValue().getWorkflowRunId()).isEqualTo("run-app");
        assertThat(meta).doesNotContainKey("pending_todos");
        verify(eventPublisher).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void approvePendingTodos_rejected_discardsPending_createsNothing() {
        WorkflowRun run = runWithPending(pending("HIGH", "general"));
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));

        approvalService.approvePendingTodos("run-app", false);

        assertThat(run.getMetadata()).doesNotContainKey("pending_todos");
        verify(workflowRunRepository).save(run);
        verify(todoRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void approvePendingTodos_missingRun_throwsResourceNotFound() {
        when(workflowRunRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> approvalService.approvePendingTodos("nope", true))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void approvePendingTodos_existingByCategory_updatesInPlace_noEvent() {
        WorkflowRun run = runWithPending(pending("MEDIUM", "general"));
        Todo existing = Todo.builder().id("t-old").title("Old title").priority(Todo.Priority.LOW).build();
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));
        when(todoRepository.findOpenGateByCompositeKey("proj-1", "workflow_run", "wf-1", "category:general"))
                .thenReturn(Optional.of(existing));

        approvalService.approvePendingTodos("run-app", true);

        assertThat(existing.getTitle()).isEqualTo("Old title");
        assertThat(existing.getDescription()).isEqualTo("from pending");
        assertThat(existing.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
        assertThat(existing.getWorkflowRunId()).isEqualTo("run-app");
        assertThat(existing.getTags()).isEqualTo("[]");
        verify(todoRepository).save(existing);
        verify(eventPublisher, never()).publishEvent(any());
    }

    @Test
    void approvePendingTodos_invalidPriority_throwsIllegalArgument() {
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(runWithPending(pending("URGENT", "general"))));

        assertThatThrownBy(() -> approvalService.approvePendingTodos("run-app", true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void approvePendingTodos_missingCategory_usesLiteralNullTag() {
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(runWithPending(pending("HIGH", null))));
        when(todoRepository.findOpenGateByCompositeKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        approvalService.approvePendingTodos("run-app", true);

        verify(todoRepository).findOpenGateByCompositeKey("proj-1", "workflow_run", "wf-1", "category:null");
    }

    @Test
    void approvePendingTodos_isAnnotatedTransactional() throws NoSuchMethodException {
        var m = PendingTodoApprovalService.class.getMethod("approvePendingTodos", String.class, boolean.class);

        assertThat(m.isAnnotationPresent(Transactional.class)).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void storePendingTodos_redGate_buildsIncidentProposalWithExactKeys() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "run-app", "wf-1", "Test Workflow",
                "proj-1", "/workspace", RunStatus.FAILED, Map.of(), Map.of(), Map.of());
        WorkflowRun run = new WorkflowRun();
        run.setId("run-app");
        run.setMetadata(new HashMap<>());
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> inv.getArgument(0));

        approvalService.storePendingTodos(event, new GateResult(GateStatus.RED, "lint-failure", "3 ESLint errors"), List.of("proj-1", "proj-2"));

        List<Map<String, Object>> stored = (List<Map<String, Object>>) run.getMetadata().get("pending_todos");
        assertThat(stored).hasSize(2);
        assertThat(stored.get(0)).containsOnlyKeys("title", "priority", "description", "category", "gateStatus", "projectId", "tags");
        assertThat(stored.get(0)).containsEntry("title", "[Incident] Test Workflow — lint-failure")
                .containsEntry("priority", "HIGH").containsEntry("category", "lint-failure")
                .containsEntry("gateStatus", "RED").containsEntry("projectId", "proj-1");
        assertThat((String) stored.get(0).get("description")).startsWith("## RED — lint-failure\n\n3 ESLint errors");
        assertThat(stored.get(1)).containsEntry("projectId", "proj-2");
        verify(workflowRunRepository).save(run);
        verify(todoRepository, never()).save(any());
    }

    @Test
    @SuppressWarnings("unchecked")
    void storePendingTodos_yellowGate_isFollowUpMedium() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "run-app", "wf-1", "Test Workflow",
                "proj-1", "/workspace", RunStatus.COMPLETED, Map.of(), Map.of(), Map.of());
        WorkflowRun run = new WorkflowRun();
        run.setId("run-app");
        run.setMetadata(new HashMap<>());
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));

        approvalService.storePendingTodos(event, new GateResult(GateStatus.YELLOW, "tests", "flaky"), List.of("proj-1"));

        Map<String, Object> proposal = ((List<Map<String, Object>>) run.getMetadata().get("pending_todos")).get(0);
        assertThat(proposal).containsEntry("title", "[Follow-up] Test Workflow — tests").containsEntry("priority", "MEDIUM");
    }

    @Test
    void storePendingTodos_runMissing_savesNothing() {
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(this, "gone", "wf-1", "Test Workflow",
                "proj-1", "/workspace", RunStatus.FAILED, Map.of(), Map.of(), Map.of());
        when(workflowRunRepository.findById("gone")).thenReturn(Optional.empty());

        approvalService.storePendingTodos(event, new GateResult(GateStatus.RED, "x", "y"), List.of("proj-1"));

        verify(workflowRunRepository, never()).save(any());
        verify(workflowRunRepository).findById(eq("gone"));
    }
}
