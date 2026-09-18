package com.automation.service.todo;

import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.entity.Todo;
import com.automation.event.TodoCreatedEvent;
import com.automation.event.WorkflowRunCompletedEvent;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.GateStatusResolver.GateResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class GranularTodoServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private TodoSimilarityService similarityService;
    @Mock private PlatformTransactionManager transactionManager;
    @Mock private ApplicationEventPublisher eventPublisher;
    @Mock private AutoTodoTextBuilder textBuilder;

    private GranularTodoService service;

    @BeforeEach
    void setUp() {
        // TransactionTemplate runs the callback directly when the manager hands out a status
        lenient().when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        // Identity passthrough: the exact text this produces is AutoTodoTextBuilder's concern
        // (covered by AutoTodoTextBuilderTest), not GranularTodoService's.
        lenient().when(textBuilder.buildGranularDescription(any(), any())).thenAnswer(inv -> inv.getArgument(0));
        service = new GranularTodoService(todoRepository, similarityService, transactionManager, eventPublisher, textBuilder);
    }

    private static WorkflowRunCompletedEvent event(List<Map<String, Object>> items) {
        return new WorkflowRunCompletedEvent(new Object(), "run-1", "wf-1", "Audit", "p-1", "/p",
                RunStatus.COMPLETED, Map.of("todo_items", items), Map.of(), null);
    }

    @Test
    void hasItems_falseForMissingOrEmptyList() {
        assertThat(service.hasItems(event(List.of()))).isFalse();
    }

    @Test
    void syncItems_closesOpenItemTodosNoLongerReported() {
        Todo stale = Todo.builder().id("old").title("Alt").status(Todo.Status.OPEN)
                .tags("[\"auto:workflow\",\"item:1\"]").build();
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                "p-1", Todo.Source.WORKFLOW, "workflow_run", "wf-1", Todo.Status.DONE)).thenReturn(List.of(stale));

        service.syncItems("p-1", event(List.of()), new GateResult(GateStatus.YELLOW, "quality", "d"));

        assertThat(stale.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(stale);
        verify(similarityService, never()).findSimilar(any(), any(), any());
    }

    @Test
    void granularTodos_newFinding_createsTodo() {
        Map<String, Object> todoItem = Map.of(
                "title", "Fix SQL injection",
                "priority", "HIGH",
                "tags", List.of("security"),
                "description", "Found SQL injection in UserController");

        WorkflowRunCompletedEvent event = event(List.of(todoItem));
        GateResult gate = new GateResult(GateStatus.RED, "security", "Issues found");
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(similarityService.findSimilar(anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        List<Todo> saved = captor.getAllValues();
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

        WorkflowRunCompletedEvent event = event(List.of(todoItem));
        GateResult gate = new GateResult(GateStatus.YELLOW, "quality", "Warnings");

        String compositeTag = "item:" + "Fix bug".hashCode();
        Todo existing = Todo.builder()
                .id("existing-1")
                .title("Fix bug")
                .description("Old description")
                .status(Todo.Status.OPEN)
                .priority(Todo.Priority.LOW)
                .build();
        when(todoRepository.findOpenByCompositeKey(eq("p-1"), eq("workflow_run"), eq("wf-1"), eq(compositeTag)))
                .thenReturn(Optional.of(existing));
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        // The existing todo should have been updated
        assertThat(captor.getAllValues()).anyMatch(t ->
                "existing-1".equals(t.getId()) && t.getDescription().contains("Updated description"));
        verify(similarityService, never()).findSimilar(any(), any(), any());
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

        WorkflowRunCompletedEvent event = event(List.of(todoItem));
        GateResult gate = new GateResult(GateStatus.RED, "perf", "Issues");

        // No composite key match
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // But similarity service finds a SAME match
        Todo similarTodo = Todo.builder()
                .id("similar-1")
                .title("Fix memory leak in pool")
                .description("Original description")
                .priority(Todo.Priority.MEDIUM)
                .build();
        when(similarityService.findSimilar(eq("p-1"), eq("Fix memory leak"), any()))
                .thenReturn(List.of(new TodoSimilarityService.SimilarTodo(
                        similarTodo, 0.9, TodoSimilarityService.Similarity.SAME)));

        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

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

        WorkflowRunCompletedEvent event = event(items);
        GateResult gate = new GateResult(GateStatus.RED, "hunt-quality", "Issues");

        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        Todo first = Todo.builder().id("first-1").title("Hunt rebuild: hornberg").build();
        when(similarityService.findSimilar(eq("p-1"), startsWith("Hunt rebuild:"), any()))
                .thenAnswer(inv -> "Hunt rebuild: hornberg".equals(inv.getArgument(1))
                        ? List.of()
                        : List.of(new TodoSimilarityService.SimilarTodo(
                                first, 0.9, TodoSimilarityService.Similarity.SAME)));

        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        List<String> createdTitles = captor.getAllValues().stream()
                .map(Todo::getTitle).filter(t -> t != null && t.startsWith("Hunt rebuild:")).toList();
        assertThat(createdTitles).contains(
                "Hunt rebuild: hornberg", "Hunt rebuild: wesel", "Hunt rebuild: bad-windsheim");
        // 3 granular creations. The aggregate gate todo (upsertTodo) is a
        // separate concern of AutoTodoService, not exercised here.
        verify(eventPublisher, times(3)).publishEvent(any(TodoCreatedEvent.class));
    }

    @Test
    void granularTodos_relatedMatch_setsRelatedTodoId() {
        Map<String, Object> todoItem = Map.of(
                "title", "Add unit tests",
                "priority", "MEDIUM",
                "tags", List.of("testing"));

        WorkflowRunCompletedEvent event = event(List.of(todoItem));
        GateResult gate = new GateResult(GateStatus.YELLOW, "coverage", "Low");

        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        // Similarity service finds a RELATED match
        Todo relatedTodo = Todo.builder()
                .id("related-1")
                .title("Improve test coverage")
                .build();
        when(similarityService.findSimilar(eq("p-1"), eq("Add unit tests"), any()))
                .thenReturn(List.of(new TodoSimilarityService.SimilarTodo(
                        relatedTodo, 0.6, TodoSimilarityService.Similarity.RELATED)));

        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any()))
                .thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(t ->
                "Add unit tests".equals(t.getTitle())
                && "related-1".equals(t.getRelatedTodoId()));
    }

    /**
     * Wrapper-Bloecke (Komprimierung, Weiterreichen einer Ausgabe) kuerzen oder verstellen Titel:
     * ein gekuerzter Titel ist eine Dublette derselben Zeile, kein zweites TODO (TODO 8d02ed3f).
     */
    @Test
    void granularTodos_truncatedTitleInSameRun_createsOnlyTheLongerTodo() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Timeout im Workflow-Executor behebt sich nicht von selbst",
                        "priority", "HIGH", "tags", List.of("backend")),
                Map.of("title", "Timeout im Workflow-Executor behebt…", "priority", "HIGH", "tags", List.of("backend")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactly("Timeout im Workflow-Executor behebt sich nicht von selbst");
    }

    @Test
    void granularTodos_titleDifferingOnlyInCaseAndWhitespace_createsOneTodo() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Fix SQL injection", "priority", "HIGH", "tags", List.of("security")),
                Map.of("title", "  fix   SQL   INJECTION ", "priority", "HIGH", "tags", List.of("security")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactly("Fix SQL injection");
    }

    /**
     * Die Praefix-Regel greift erst ab {@code PREFIX_MIN_LENGTH} = 20 Zeichen: „timeout im executor"
     * (19) ist als gemeinsamer Anfang noch zu wenig, um einen eigenen Befund zu verwerfen.
     */
    @Test
    void granularTodos_truncatedTitleJustBelowThePrefixMinimum_staysItsOwnTodo() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Timeout im Executor…", "priority", "HIGH", "tags", List.of("backend")),
                Map.of("title", "Timeout im Executor behebt sich nicht", "priority", "HIGH", "tags", List.of("backend")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactlyInAnyOrder(
                "Timeout im Executor…", "Timeout im Executor behebt sich nicht");
    }

    /** Ein Zeichen mehr gemeinsamer Anfang (21) — jetzt ist es die gekuerzte Fassung. */
    @Test
    void granularTodos_truncatedTitleJustAboveThePrefixMinimum_isDropped() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Timeout im Executor A…", "priority", "HIGH", "tags", List.of("backend")),
                Map.of("title", "Timeout im Executor A behebt sich nicht", "priority", "HIGH", "tags", List.of("backend")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactly("Timeout im Executor A behebt sich nicht");
    }

    /**
     * Ohne Kuerzungszeichen ist ein gemeinsamer Anfang kein Beweis: „Fehler in Modul A beheben" ist
     * ein eigener Befund mit eigener Prioritaet und Beschreibung, auch wenn ein zweiter Titel damit
     * anfaengt. Nur ein mit „…"/„..." abgeschnittener Titel ist nachweislich dieselbe Zeile.
     */
    @Test
    void granularTodos_longerTitleContinuingAShorterOne_staysTwoTodos() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Fehler in Modul A beheben", "priority", "HIGH", "tags", List.of("backend")),
                Map.of("title", "Fehler in Modul A beheben, Teil 2", "priority", "LOW", "tags", List.of("backend")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactlyInAnyOrder(
                "Fehler in Modul A beheben", "Fehler in Modul A beheben, Teil 2");
    }

    /** Gegenprobe: ein gemeinsamer kurzer Anfang macht aus zwei Befunden keinen einzigen. */
    @Test
    void granularTodos_differentTitles_stayTwoTodos() {
        List<Map<String, Object>> items = List.of(
                Map.of("title", "Hunt rebuild: hornberg", "priority", "HIGH", "tags", List.of("hunt")),
                Map.of("title", "Hunt rebuild: wesel", "priority", "HIGH", "tags", List.of("hunt")));

        List<String> created = createdTitles(items);

        assertThat(created).containsExactlyInAnyOrder("Hunt rebuild: hornberg", "Hunt rebuild: wesel");
    }

    /** @return die Titel der in diesem Lauf neu angelegten granularen TODOs */
    private List<String> createdTitles(List<Map<String, Object>> items) {
        when(todoRepository.findOpenByCompositeKey(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(similarityService.findSimilar(anyString(), anyString(), any())).thenReturn(List.of());
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                anyString(), any(), anyString(), anyString(), any())).thenReturn(List.of());
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event(items), new GateResult(GateStatus.RED, "quality", "Issues"));

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        return captor.getAllValues().stream().map(Todo::getTitle).filter(java.util.Objects::nonNull).toList();
    }

    @Test
    void granularTodos_resolvedItem_closedTodo() {
        // Item was in previous run but not in current → should be closed
        WorkflowRunCompletedEvent event = event(List.of()); // Empty = all resolved
        GateResult gate = new GateResult(GateStatus.GREEN, "quality", "All clean");

        Todo previousTodo = Todo.builder()
                .id("prev-1")
                .title("Old finding")
                .status(Todo.Status.OPEN)
                .tags("[\"auto:workflow\",\"item:12345\"]")
                .build();
        when(todoRepository.findByProjectIdAndSourceAndReferenceTypeAndReferenceIdAndStatusNot(
                eq("p-1"), eq(Todo.Source.WORKFLOW), eq("workflow_run"), eq("wf-1"), eq(Todo.Status.DONE)))
                .thenReturn(List.of(previousTodo));
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.syncItems("p-1", event, gate);

        ArgumentCaptor<Todo> captor = ArgumentCaptor.forClass(Todo.class);
        verify(todoRepository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getAllValues()).anyMatch(t ->
                "prev-1".equals(t.getId()) && t.getStatus() == Todo.Status.DONE);
    }
}
