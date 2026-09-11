package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowRun;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.AppSettingsRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AutoTodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private GateStatusResolver gateStatusResolver;

    @Mock
    private ProjectPathResolver projectPathResolver;

    @Mock
    private StepResultRepository stepResultRepository;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private GranularTodoService granularTodoService;

    private AutoTodoService autoTodoService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        AutoTodoTextBuilder textBuilder = new AutoTodoTextBuilder(stepResultRepository);
        autoTodoService = new AutoTodoService(
                todoRepository, gateStatusResolver,
                projectPathResolver, appSettingsRepository,
                transactionManager, eventPublisher, textBuilder,
                new PendingTodoApprovalService(todoRepository, workflowRunRepository, transactionManager,
                        eventPublisher, textBuilder),
                granularTodoService);
    }

    private static final Map<String, Object> AUTO_TODO_ENABLED_META = Map.of("autoTodo", Map.of("enabled", true));
    private static final Map<String, Object> AUTO_TODO_TRACK_META = Map.of("autoTodo", Map.of("enabled", true, "mode", "track"));

    @Test
    void toggleDisabled_noTodoCreated() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of(), Map.of());

        autoTodoService.onWorkflowRunCompleted(event);

        verifyNoInteractions(todoRepository);
    }

    @Test
    void cancelledStatus_noTodoCreated() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.CANCELLED, Map.of(), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event)).thenReturn(null);

        autoTodoService.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).save(any());
    }

    @Test
    void redGate_createsHighPriorityTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "RED"), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "lint-failure", "3 ESLint errors"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo todo = captor.getValue();
        assertThat(todo.getTitle()).contains("[Incident]").contains("lint-failure");
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(todo.getSource()).isEqualTo(Todo.Source.WORKFLOW);
        assertThat(todo.getSourceAgent()).isEqualTo("auto-todo");
        assertThat(todo.getReferenceType()).isEqualTo("workflow_run");
        assertThat(todo.getReferenceId()).isEqualTo("wf-1");
        assertThat(todo.getTags()).contains("auto:workflow").contains("gate:red").contains("category:lint-failure");
    }

    @Test
    void yellowGate_createsMediumPriorityTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "YELLOW"), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.YELLOW, "test-warning", "2 flaky tests"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo todo = captor.getValue();
        assertThat(todo.getTitle()).contains("[Follow-up]").contains("test-warning");
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
        assertThat(todo.getTags()).contains("gate:yellow");
    }

    @Test
    void greenGate_closesOpenTodos() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of(), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.GREEN, "success", null));

        Todo openTodo = Todo.builder()
                .id("todo-1")
                .title("[Incident] Test — lint-failure")
                .status(Todo.Status.OPEN)
                .source(Todo.Source.WORKFLOW)
                .tags("[\"auto:workflow\",\"gate:red\",\"category:lint-failure\"]")
                .referenceType("workflow_run")
                .referenceId("wf-1")
                .projectId("proj-1")
                .build();
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                "proj-1", Todo.Source.WORKFLOW, "workflow_run", "wf-1", Todo.Status.DONE))
                .thenReturn(List.of(openTodo));
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(Todo.Status.DONE);
    }

    @Test
    void greenGate_doesNotCloseNonAutoTodos() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of(), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.GREEN, "success", null));

        // A manually created workflow todo (no auto:workflow tag)
        Todo manualTodo = Todo.builder()
                .id("todo-2")
                .title("Manual TODO")
                .status(Todo.Status.OPEN)
                .source(Todo.Source.WORKFLOW)
                .tags(null)
                .referenceType("workflow_run")
                .referenceId("wf-1")
                .projectId("proj-1")
                .build();
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                "proj-1", Todo.Source.WORKFLOW, "workflow_run", "wf-1", Todo.Status.DONE))
                .thenReturn(List.of(manualTodo));

        autoTodoService.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).save(any());
    }

    @Test
    void redGate_explicitGateDescription_winsOverStepHeuristic() {
        // Regression: loadReportFromSteps() guesses the report by position (second-to-last
        // step) and used to override the workflow's explicit gate_description. Observed with
        // self-care-platform-health: the incident showed the GREEN frontend build (step 1)
        // instead of the LLM verdict's precise backend-test diagnosis.
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "RED"), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "backend-test-failure",
                        "1 Failure + 159 Errors (ApplicationContext-Startup-Crash)"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Step layout like self-care-platform-health: step 1 (second-to-last) is the
        // misleading green frontend build — the position heuristic would pick it.
        String greenBuildOutput = "dist/assets/index.js 457 kB\n✓ built in 8.56s\n"
                + "=== LINT ===\n✖ 26 problems (0 errors, 26 warnings)\n"
                + "x".repeat(200);
        lenient().when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of(
                        StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                                .output("mvn test failure output " + "y".repeat(300)).build(),
                        StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                                .output(greenBuildOutput).build(),
                        StepResult.builder().stepIndex(2).status(StepStatus.COMPLETED)
                                .output("LLM verdict").build()));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription())
                .contains("1 Failure + 159 Errors (ApplicationContext-Startup-Crash)")
                .doesNotContain("built in 8.56s");
    }

    @Test
    void redGate_noGateDescription_fallsBackToStepReport() {
        // Without an explicit gate_description the step heuristic remains the source.
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED,
                Map.of(), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "execution-failure", null));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of(
                        StepResult.builder().stepIndex(0).status(StepStatus.COMPLETED)
                                .output("data collection").build(),
                        StepResult.builder().stepIndex(1).status(StepStatus.COMPLETED)
                                .output("analysis report content " + "z".repeat(300)).build(),
                        StepResult.builder().stepIndex(2).status(StepStatus.FAILED)
                                .output("verdict shell failed").build()));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        assertThat(captor.getValue().getDescription()).contains("analysis report content");
    }

    @Test
    void dedupe_updateExistingTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "RED"), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "lint-failure", "5 errors now"));

        Todo existingTodo = Todo.builder()
                .id("todo-existing")
                .title("[Incident] Test Workflow — lint-failure")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.HIGH)
                .build();
        when(todoRepository.findOpenByCompositeKey("proj-1", "workflow_run", "wf-1", "category:lint-failure"))
                .thenReturn(Optional.of(existingTodo));
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo updated = captor.getValue();
        assertThat(updated.getId()).isEqualTo("todo-existing");
        assertThat(updated.getWorkflowRunId()).isEqualTo("run-1");
        assertThat(updated.getDescription()).contains("5 errors now");
    }

    @Test
    void globalDefault_enablesAutoTodoWhenNoExplicitSetting() {
        // Workflow has no autoTodo in metadata — fallback to global default
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, Map.of(), Map.of());
        when(appSettingsRepository.get("auto-todo-default")).thenReturn(Optional.of("true"));
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "execution-failure", "Process exited with code 1"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    void globalDefault_disabledDoesNotCreateTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, Map.of(), Map.of());
        when(appSettingsRepository.get("auto-todo-default")).thenReturn(Optional.of("false"));

        autoTodoService.onWorkflowRunCompleted(event);

        verifyNoInteractions(todoRepository);
    }

    @Test
    void explicitDisable_overridesGlobalDefault() {
        // Workflow explicitly sets autoTodo.enabled = false, even with global default true
        Map<String, Object> meta = Map.of("autoTodo", Map.of("enabled", false));
        WorkflowRunCompletedEvent event = createEvent(RunStatus.FAILED, Map.of(), meta);

        autoTodoService.onWorkflowRunCompleted(event);

        verifyNoInteractions(todoRepository);
        verifyNoInteractions(appSettingsRepository);
    }

    @Test
    void trackMode_greenGate_createsLowPriorityTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of(), AUTO_TODO_TRACK_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.GREEN, "test-coverage", "Coverage 65%"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo todo = captor.getValue();
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.LOW);
        assertThat(todo.getTitle()).contains("test-coverage");
    }

    @Test
    void trackMode_yellowGate_createsMediumPriorityTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "YELLOW"), AUTO_TODO_TRACK_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.YELLOW, "architecture", "3 HIGH findings"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo todo = captor.getValue();
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
    }

    @Test
    void trackMode_redGate_createsHighPriorityTodo() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "RED"), AUTO_TODO_TRACK_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.RED, "security", "Critical vulnerability"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository).save(captor.capture());
        Todo todo = captor.getValue();
        assertThat(todo.getPriority()).isEqualTo(Todo.Priority.HIGH);
    }

    @Test
    void trackMode_greenGate_doesNotCloseExistingTodos() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, Map.of(), AUTO_TODO_TRACK_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.GREEN, "test-coverage", "All good"));
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        // Should create/upsert, NOT close
        verify(todoRepository, never()).findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any());
        verify(todoRepository).save(any(Todo.class));
    }

    @Test
    void autoMode_withItems_delegatesToGranularService() {
        WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED,
                Map.of("gate_status", "YELLOW"), AUTO_TODO_ENABLED_META);
        when(gateStatusResolver.resolve(event))
                .thenReturn(new GateResult(GateStatus.YELLOW, "quality", "Warnings"));
        when(granularTodoService.hasItems(any())).thenReturn(true);
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        verify(granularTodoService).syncItems(eq("proj-1"), eq(event), any());
    }

    @Test
    void todoTriggerRun_loopPrevention_skipsProcessing() {
        Map<String, Object> runMeta = new HashMap<>();
        runMeta.put("todoTriggerRun", true);
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                this, "run-loop", "wf-1", "Test Workflow", "proj-1", "/workspace/test",
                RunStatus.FAILED, runMeta, AUTO_TODO_ENABLED_META, Map.of());

        autoTodoService.onWorkflowRunCompleted(event);

        verify(todoRepository, never()).save(any());
        verify(gateStatusResolver, never()).resolve(any());
    }

    @Test
    void approvalMode_redGate_storesPendingTodosInsteadOfCreating() {
        Map<String, Object> approvalMeta = Map.of("autoTodo", Map.of("enabled", true, "mode", "approval"));
        WorkflowRunCompletedEvent event = new WorkflowRunCompletedEvent(
                this, "run-app", "wf-1", "Test Workflow", "proj-1", "/workspace/test",
                RunStatus.FAILED, Map.of(), approvalMeta, Map.of());
        when(gateStatusResolver.resolve(any())).thenReturn(new GateResult(GateStatus.RED, "lint-failure", "3 ESLint errors"));

        WorkflowRun run = new WorkflowRun();
        run.setId("run-app");
        Map<String, Object> runMetadata = new HashMap<>();
        run.setMetadata(runMetadata);
        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.onWorkflowRunCompleted(event);

        assertThat(runMetadata).containsKey("pending_todos");
        verify(todoRepository, never()).save(any());
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status,
                                                   Map<String, Object> runMetadata,
                                                   Map<String, Object> workflowMetadata) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow", "proj-1", "/workspace/test",
                status, runMetadata, workflowMetadata, Map.of());
    }
}
