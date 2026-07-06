package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
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
    private TodoSimilarityService similarityService;

    @Mock
    private AppSettingsRepository appSettingsRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private PlatformTransactionManager transactionManager;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    private AutoTodoService autoTodoService;

    @BeforeEach
    void setUp() {
        lenient().when(transactionManager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
        autoTodoService = new AutoTodoService(
                todoRepository, stepResultRepository, gateStatusResolver,
                similarityService, projectPathResolver, appSettingsRepository,
                workflowRunRepository, transactionManager, eventPublisher);
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
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of());

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
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of());

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
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of());

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
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("run-1"))
                .thenReturn(List.of());

        autoTodoService.onWorkflowRunCompleted(event);

        // Should create/upsert, NOT close
        verify(todoRepository, never()).findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any());
        verify(todoRepository).save(any(Todo.class));
    }

    @Nested
    class GranularTodoTests {

        @Test
        void granularTodos_newFinding_createsTodo() {
            Map<String, Object> todoItem = Map.of(
                    "title", "Fix SQL injection",
                    "priority", "HIGH",
                    "tags", List.of("security"),
                    "description", "Found SQL injection in UserController");

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.RED, "security", "Issues found"));
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(similarityService.findSimilar(anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            List<Todo> saved = captor.getAllValues();
            // Should have at least the granular todo
            assertThat(saved).anyMatch(t -> "Fix SQL injection".equals(t.getTitle()));
            Todo granular = saved.stream().filter(t -> "Fix SQL injection".equals(t.getTitle())).findFirst().orElseThrow();
            assertThat(granular.getPriority()).isEqualTo(Todo.Priority.HIGH);
            assertThat(granular.getDescription()).contains("SQL injection in UserController");
            assertThat(granular.getRelatedTodoId()).isNull();
        }

        @Test
        void granularTodos_existingByCompositeKey_updates() {
            Map<String, Object> todoItem = Map.of(
                    "title", "Fix bug",
                    "priority", "MEDIUM",
                    "tags", List.of("backend"),
                    "description", "Updated description");

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.YELLOW, "quality", "Warnings"));

            String compositeTag = "item:" + "Fix bug".hashCode();
            Todo existing = Todo.builder()
                    .id("existing-1")
                    .title("Fix bug")
                    .description("Old description")
                    .status(Todo.Status.OPEN)
                    .priority(Todo.Priority.LOW)
                    .build();
            when(todoRepository.findOpenByCompositeKey(eq("proj-1"), eq("workflow_run"), eq("wf-1"), eq(compositeTag)))
                    .thenReturn(Optional.of(existing));
            when(todoRepository.findOpenByCompositeKey(eq("proj-1"), eq("workflow_run"), eq("wf-1"), startsWith("category:")))
                    .thenReturn(Optional.empty());
            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            // The existing todo should have been updated
            assertThat(captor.getAllValues()).anyMatch(t ->
                    "existing-1".equals(t.getId()) && t.getDescription().contains("Updated description"));
        }

        @Test
        void granularTodos_similarSameMatch_createsOwnTodoWithLink() {
            // SAME matches must NOT merge anymore: merging swallowed the
            // TodoCreatedEvent (TodoTriggers never fired) and collapsed the
            // items of one batch into a single todo. SAME now only links.
            Map<String, Object> todoItem = Map.of(
                    "title", "Fix memory leak",
                    "priority", "HIGH",
                    "tags", List.of("performance"));

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.RED, "perf", "Issues"));

            // No composite key match
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), startsWith("item:")))
                    .thenReturn(Optional.empty());
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), startsWith("category:")))
                    .thenReturn(Optional.empty());

            // But similarity service finds a SAME match
            Todo similarTodo = Todo.builder()
                    .id("similar-1")
                    .title("Fix memory leak in pool")
                    .description("Original description")
                    .priority(Todo.Priority.MEDIUM)
                    .build();
            when(similarityService.findSimilar(eq("proj-1"), eq("Fix memory leak"), any()))
                    .thenReturn(List.of(new TodoSimilarityService.SimilarTodo(
                            similarTodo, 0.9, TodoSimilarityService.Similarity.SAME)));

            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            // A NEW todo is created, linked to the similar one — the similar
            // todo itself is never modified.
            assertThat(captor.getAllValues()).anyMatch(t ->
                    "Fix memory leak".equals(t.getTitle())
                    && "similar-1".equals(t.getRelatedTodoId()));
            assertThat(captor.getAllValues()).noneMatch(t -> "similar-1".equals(t.getId()));
            // The TodoCreatedEvent must fire so TodoTriggers can react
            verify(eventPublisher, atLeastOnce()).publishEvent(any(TodoCreatedEvent.class));
        }

        @Test
        void granularTodos_batchItems_allCreatedDespiteSameMatches() {
            // Regression for the batch collapse: 3 items in one run, the
            // similarity check flags items 2+3 as SAME to item 1 — all three
            // must still become their own todos.
            List<Map<String, Object>> items = List.of(
                    Map.of("title", "Hunt rebuild: hornberg", "priority", "HIGH", "tags", List.of("hunt-rebuild")),
                    Map.of("title", "Hunt rebuild: wesel", "priority", "HIGH", "tags", List.of("hunt-rebuild")),
                    Map.of("title", "Hunt rebuild: bad-windsheim", "priority", "HIGH", "tags", List.of("hunt-rebuild")));

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", items);

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.RED, "hunt-quality", "Issues"));

            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());

            Todo first = Todo.builder().id("first-1").title("Hunt rebuild: hornberg").build();
            when(similarityService.findSimilar(eq("proj-1"), startsWith("Hunt rebuild:"), any()))
                    .thenAnswer(inv -> "Hunt rebuild: hornberg".equals(inv.getArgument(1))
                            ? List.of()
                            : List.of(new TodoSimilarityService.SimilarTodo(
                                    first, 0.9, TodoSimilarityService.Similarity.SAME)));

            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            List<String> createdTitles = captor.getAllValues().stream()
                    .map(Todo::getTitle).filter(t -> t != null && t.startsWith("Hunt rebuild:")).toList();
            assertThat(createdTitles).contains(
                    "Hunt rebuild: hornberg", "Hunt rebuild: wesel", "Hunt rebuild: bad-windsheim");
            // 3 granular events + 1 for the RED-gate incident aggregate (upsertTodo)
            verify(eventPublisher, times(4)).publishEvent(any(TodoCreatedEvent.class));
        }

        @Test
        void granularTodos_relatedMatch_setsRelatedTodoId() {
            Map<String, Object> todoItem = Map.of(
                    "title", "Add unit tests",
                    "priority", "MEDIUM",
                    "tags", List.of("testing"));

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.YELLOW, "coverage", "Low"));

            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), startsWith("item:")))
                    .thenReturn(Optional.empty());
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), startsWith("category:")))
                    .thenReturn(Optional.empty());

            // Similarity service finds a RELATED match
            Todo relatedTodo = Todo.builder()
                    .id("related-1")
                    .title("Improve test coverage")
                    .build();
            when(similarityService.findSimilar(eq("proj-1"), eq("Add unit tests"), any()))
                    .thenReturn(List.of(new TodoSimilarityService.SimilarTodo(
                            relatedTodo, 0.6, TodoSimilarityService.Similarity.RELATED)));

            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(t ->
                    "Add unit tests".equals(t.getTitle())
                    && "related-1".equals(t.getRelatedTodoId()));
        }

        @Test
        void granularTodos_resolvedItem_closedTodo() {
            // Item was in previous run but not in current → should be closed
            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of()); // Empty = all resolved

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.GREEN, "quality", "All clean"));

            Todo previousTodo = Todo.builder()
                    .id("prev-1")
                    .title("Old finding")
                    .status(Todo.Status.OPEN)
                    .tags("[\"auto:workflow\",\"item:12345\"]")
                    .build();
            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    eq("proj-1"), eq(Todo.Source.WORKFLOW), eq("workflow_run"), eq("wf-1"), eq(Todo.Status.DONE)))
                    .thenReturn(List.of(previousTodo));
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(t ->
                    "prev-1".equals(t.getId()) && t.getStatus() == Todo.Status.DONE);
        }

        @Test
        void buildGranularDescription_containsWorkflowMetadata() {
            Map<String, Object> todoItem = Map.of(
                    "title", "Test item",
                    "priority", "LOW",
                    "tags", List.of(),
                    "description", "Item description");

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.GREEN, "ok", null));
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(similarityService.findSimilar(anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            assertThat(captor.getAllValues()).anyMatch(t ->
                    "Test item".equals(t.getTitle())
                    && t.getDescription().contains("Test Workflow")
                    && t.getDescription().contains("run-1"));
        }

        @Test
        void buildGranularDescription_footerContainsClickableRunAndWorkflowLinks() {
            Map<String, Object> todoItem = Map.of(
                    "title", "Test item",
                    "priority", "LOW",
                    "tags", List.of(),
                    "description", "Item description");

            Map<String, Object> runMeta = new HashMap<>();
            runMeta.put("todo_items", List.of(todoItem));

            WorkflowRunCompletedEvent event = createEvent(RunStatus.COMPLETED, runMeta, AUTO_TODO_ENABLED_META);
            when(gateStatusResolver.resolve(event))
                    .thenReturn(new GateResult(GateStatus.GREEN, "ok", null));
            when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(Optional.empty());
            when(similarityService.findSimilar(anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                    anyString(), any(), anyString(), anyString(), any()))
                    .thenReturn(List.of());
            when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            autoTodoService.onWorkflowRunCompleted(event);

            ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
            verify(todoRepository, atLeastOnce()).save(captor.capture());
            // Workflow link uses workflowId; Run link uses workflowId/run/runId. Relative URLs work
            // on every instance (Dev :3000, Stable :9091, Test :8090).
            assertThat(captor.getAllValues()).anyMatch(t ->
                    t.getDescription().contains("[Test Workflow](/workflows/wf-1)")
                    && t.getDescription().contains("[Run →](/workflows/wf-1/run/run-1)"));
        }
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

    @Test
    void approvePendingTodos_approved_createsTodosFromPendingMap() {
        Workflow workflow = new Workflow();
        workflow.setId("wf-1");
        WorkflowRun run = new WorkflowRun();
        run.setId("run-app");
        run.setWorkflow(workflow);
        Map<String, Object> meta = new HashMap<>();
        Map<String, Object> pending = new HashMap<>();
        pending.put("title", "Approved Test Todo");
        pending.put("priority", "HIGH");
        pending.put("description", "from pending");
        pending.put("projectId", "proj-1");
        pending.put("category", "general");
        pending.put("tags", "[]");
        meta.put("pending_todos", List.of(pending));
        run.setMetadata(meta);

        when(workflowRunRepository.findById("run-app")).thenReturn(Optional.of(run));
        when(todoRepository.findOpenByCompositeKey(any(), any(), any(), any())).thenReturn(Optional.empty());
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            t.setId("todo-new");
            return t;
        });
        when(workflowRunRepository.save(any(WorkflowRun.class))).thenAnswer(inv -> inv.getArgument(0));

        autoTodoService.approvePendingTodos("run-app", true);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getTitle()).isEqualTo("Approved Test Todo");
        assertThat(meta).doesNotContainKey("pending_todos");
    }

    private WorkflowRunCompletedEvent createEvent(RunStatus status,
                                                   Map<String, Object> runMetadata,
                                                   Map<String, Object> workflowMetadata) {
        return new WorkflowRunCompletedEvent(
                this, "run-1", "wf-1", "Test Workflow", "proj-1", "/workspace/test",
                status, runMetadata, workflowMetadata, Map.of());
    }
}
