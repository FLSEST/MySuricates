package com.automation.service.todo;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.TodoCountResponse;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private TodoResponseAssembler responseAssembler;

    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Mock
    private TodoGoalConsistency goalConsistency;

    /** Modul goals an; einzelne Tests schalten es aus (TODO c60bf914). */
    @org.mockito.Spy private com.automation.modules.ModuleRegistry modules = com.automation.modules.ModuleRegistry.allEnabled();

    @InjectMocks
    private TodoService todoService;

    // --- findAll ---

    /**
     * The list order lives in the specification ({@link TodoSpecifications#listSpec}) because
     * the business order CRITICAL &gt; HIGH &gt; MEDIUM &gt; LOW needs a CASE expression. A sorted
     * {@code Pageable} would silently overwrite that order-by, so it must stay unsorted (aa434aee).
     */
    @Test
    void findAll_passesUnsortedPageable_soTheSpecOrderSurvives() {
        when(todoRepository.findAll(ArgumentMatchers.<Specification<Todo>>any(), any(Pageable.class)))
                .thenReturn(Page.<Todo>empty());

        todoService.findAll(1, 25, null, null, null, null, null, null);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(todoRepository).findAll(ArgumentMatchers.<Specification<Todo>>any(), pageable.capture());
        assertThat(pageable.getValue().getSort().isUnsorted()).isTrue();
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(25);
    }

    /**
     * TODO 4e7a7401: an unknown filter value used to fail inside the query, where Spring Data wraps
     * the IllegalArgumentException, and the API answered 500. It is parsed before the query now, and
     * the message names the allowed values.
     */
    @Test
    void findAll_unknownFilterValue_failsBeforeTheQueryNamingTheAllowedValues() {
        assertThatThrownBy(() -> todoService.findAll(0, 50, null, List.of("OPEN", "FOO"), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("status muss OPEN, IN_PROGRESS, DONE, BACKLOG oder ARCHIVED sein, nicht 'FOO'");
        assertThatThrownBy(() -> todoService.findAll(0, 50, null, null, "URGENT", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("priority muss LOW, MEDIUM, HIGH oder CRITICAL sein, nicht 'URGENT'");
        assertThatThrownBy(() -> todoService.findAll(0, 50, null, null, null, "HUMAN", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("source muss USER, AI oder WORKFLOW sein, nicht 'HUMAN'");
        verify(todoRepository, never()).findAll(ArgumentMatchers.<Specification<Todo>>any(), any(Pageable.class));
    }

    // --- findById ---

    @Test
    void findById_existing_returnsTodo() {
        Todo todo = Todo.builder().id("t-1").title("Test").build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));

        Todo result = todoService.findById("t-1");

        assertThat(result.getId()).isEqualTo("t-1");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(todoRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- create ---

    @Test
    void create_withDefaults_setsStatusOpenPriorityMediumSourceUser() {
        CreateTodoRequest request = new CreateTodoRequest(
                "Test Todo", "Description", null, null, null,
                null, null, null, null, null, null, null, null, null);

        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> {
            Todo t = inv.getArgument(0);
            t.setId("t-new");
            return t;
        });

        Todo result = todoService.create(request);

        assertThat(result.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(result.getPriority()).isEqualTo(Todo.Priority.MEDIUM);
        assertThat(result.getSource()).isEqualTo(Todo.Source.USER);
    }

    @Test
    void create_withExplicitValues_usesProvidedValues() {
        CreateTodoRequest request = new CreateTodoRequest(
                "Important", "Desc", Todo.Status.IN_PROGRESS, Todo.Priority.HIGH,
                List.of("backend", "bug"), "proj-1", null, Todo.Source.AI, "claude-code",
                null, null, null, null, null);

        when(projectRepository.existsById("proj-1")).thenReturn(true);
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.create(request);

        assertThat(result.getTitle()).isEqualTo("Important");
        assertThat(result.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(result.getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(result.getSource()).isEqualTo(Todo.Source.AI);
        assertThat(result.getSourceAgent()).isEqualTo("claude-code");
        assertThat(result.getProjectId()).isEqualTo("proj-1");
    }

    // --- create: projectId resolution (FK guard) ---

    @Test
    void create_projectIdExactMatch_usesItAsIs() {
        String fullId = "23e55750-d31e-428b-afda-d5c04b7805df";
        when(projectRepository.existsById(fullId)).thenReturn(true);
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.create(createRequestWithProjectId(fullId));

        assertThat(result.getProjectId()).isEqualTo(fullId);
        verify(projectRepository, never()).findByIdStartingWith(any());
    }

    @Test
    void create_projectIdShortenedPrefix_expandsToFullUuid() {
        // The assistant surfaces ids shortened to 8 chars; the model echoes that
        // trimmed prefix back. It must resolve to the real project, not FK-fail.
        String fullId = "23e55750-d31e-428b-afda-d5c04b7805df";
        when(projectRepository.existsById("23e55750")).thenReturn(false);
        when(projectRepository.findByIdStartingWith("23e55750"))
                .thenReturn(List.of(Project.builder().id(fullId).build()));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.create(createRequestWithProjectId("23e55750"));

        assertThat(result.getProjectId()).isEqualTo(fullId);
    }

    @Test
    void create_projectIdUnknown_throwsResourceNotFound() {
        when(projectRepository.existsById("nope")).thenReturn(false);
        when(projectRepository.findByIdStartingWith("nope")).thenReturn(List.of());

        assertThatThrownBy(() -> todoService.create(createRequestWithProjectId("nope")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(todoRepository, never()).save(any());
    }

    @Test
    void create_projectIdAmbiguousPrefix_throwsResourceNotFound() {
        when(projectRepository.existsById("23")).thenReturn(false);
        when(projectRepository.findByIdStartingWith("23")).thenReturn(List.of(
                Project.builder().id("23e55750-d31e-428b-afda-d5c04b7805df").build(),
                Project.builder().id("2312aaaa-0000-0000-0000-000000000000").build()));

        assertThatThrownBy(() -> todoService.create(createRequestWithProjectId("23")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(todoRepository, never()).save(any());
    }

    @Test
    void create_blankProjectId_setsNullWithoutLookup() {
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.create(createRequestWithProjectId("   "));

        assertThat(result.getProjectId()).isNull();
        verify(projectRepository, never()).existsById(any());
        verify(projectRepository, never()).findByIdStartingWith(any());
    }

    private CreateTodoRequest createRequestWithProjectId(String projectId) {
        return new CreateTodoRequest(
                "Todo", null, null, null, null, projectId, null,
                Todo.Source.AI, "assistant", null, null, null, null, null);
    }

    @Test
    void update_projectIdShortenedPrefix_expandsToFullUuid() {
        String fullId = "23e55750-d31e-428b-afda-d5c04b7805df";
        Todo existing = Todo.builder().id("t-1").title("Test").build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(projectRepository.existsById("23e55750")).thenReturn(false);
        when(projectRepository.findByIdStartingWith("23e55750"))
                .thenReturn(List.of(Project.builder().id(fullId).build()));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTodoRequest request = new UpdateTodoRequest(
                null, null, null, null, null, "23e55750", null, null, null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getProjectId()).isEqualTo(fullId);
    }

    @Test
    void update_unknownProjectId_throwsResourceNotFound() {
        Todo existing = Todo.builder().id("t-1").title("Test").projectId("old").build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(projectRepository.existsById("nope")).thenReturn(false);
        when(projectRepository.findByIdStartingWith("nope")).thenReturn(List.of());

        UpdateTodoRequest request = new UpdateTodoRequest(
                null, null, null, null, null, "nope", null, null, null, null);

        assertThatThrownBy(() -> todoService.update("t-1", request))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(todoRepository, never()).save(any());
    }

    // --- update ---

    @Test
    void update_partialUpdate_onlyChangesProvidedFields() {
        Todo existing = Todo.builder()
                .id("t-1").title("Old Title").description("Old Desc")
                .status(Todo.Status.OPEN).priority(Todo.Priority.LOW)
                .build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTodoRequest request = new UpdateTodoRequest(
                "New Title", null, null, Todo.Priority.HIGH, null, null, null, null, null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getTitle()).isEqualTo("New Title");
        assertThat(result.getDescription()).isEqualTo("Old Desc");
        assertThat(result.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(result.getPriority()).isEqualTo(Todo.Priority.HIGH);
    }

    @Test
    void update_doneMarksManagedAuditFindingForVerification() {
        Todo existing = Todo.builder().id("t-1").title("Audit fix").status(Todo.Status.OPEN).build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTodoRequest request = new UpdateTodoRequest(
                null, null, Todo.Status.DONE, null, null, null, null, null, null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getStatus()).isEqualTo(Todo.Status.DONE);
        // Die Audit-Seite haengt am Domainereignis des Saves (AuditTodoCompletionListener), nicht am Service.
        assertThat(existing.domainEvents()).containsExactly(new Todo.Completed(existing));
    }

    @Test
    void update_blankProjectId_setsToNull() {
        Todo existing = Todo.builder()
                .id("t-1").title("Test").projectId("proj-1")
                .build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTodoRequest request = new UpdateTodoRequest(
                null, null, null, null, null, "", null, null, null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getProjectId()).isNull();
    }

    /** TODO c60bf914: ein Projektwechsel haelt das TODO mit seinem Ziel stimmig – nur bei echtem Wechsel. */
    @Test
    void update_projectChange_keepsTheGoalConsistent_sameProjectDoesNot() {
        Todo existing = Todo.builder().id("t-1").title("Test").projectId("proj-1").goalId("g1").build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));
        when(projectRepository.existsById(any())).thenReturn(true);

        todoService.update("t-1", new UpdateTodoRequest(null, null, null, null, null, "proj-1", null, null, null, null));
        verify(goalConsistency, never()).afterProjectChange(any());

        todoService.update("t-1", new UpdateTodoRequest(null, null, null, null, null, "proj-2", null, null, null, null));
        verify(goalConsistency).afterProjectChange(existing);
        assertThat(existing.getProjectId()).isEqualTo("proj-2");
    }

    // --- updateStatus ---

    @Test
    void updateStatus_changesStatusAndSaves() {
        Todo todo = Todo.builder().id("t-1").title("Test").status(Todo.Status.OPEN).build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.updateStatus("t-1", new UpdateTodoStatusRequest(Todo.Status.DONE, null));

        assertThat(result.getStatus()).isEqualTo(Todo.Status.DONE);
        verify(todoRepository).save(todo);
        assertThat(todo.domainEvents()).containsExactly(new Todo.Completed(todo));
    }

    // --- delete ---

    @Test
    void delete_existingTodo_deletes() {
        Todo todo = Todo.builder().id("t-1").title("Test").build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(todo));

        todoService.delete("t-1");

        verify(todoRepository).delete(todo);
    }

    @Test
    void delete_nonExistentTodo_throwsException() {
        when(todoRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> todoService.delete("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- count ---

    @Test
    void count_returnsCounts() {
        when(todoRepository.countGroupedByStatus()).thenReturn(new TodoCountResponse(5L, 3L, 12L, 2L, 0L));

        TodoCountResponse counts = todoService.count(null);

        assertThat(counts.open()).isEqualTo(5L);
        assertThat(counts.inProgress()).isEqualTo(3L);
        assertThat(counts.done()).isEqualTo(12L);
        assertThat(counts.backlog()).isEqualTo(2L);
        assertThat(counts.archived()).isZero();
    }

    @Test
    void count_withProjectId_usesProjectQuery() {
        when(todoRepository.countGroupedByStatusForProject("p-1")).thenReturn(new TodoCountResponse(1L, 0L, 2L, 0L, 0L));

        TodoCountResponse counts = todoService.count("p-1");

        assertThat(counts.open()).isEqualTo(1L);
        assertThat(counts.done()).isEqualTo(2L);
        verify(todoRepository, never()).countGroupedByStatus();
    }

    @Test
    void count_blankProjectId_countsAll() {
        when(todoRepository.countGroupedByStatus()).thenReturn(new TodoCountResponse(4L, 0L, 0L, 0L, 0L));

        assertThat(todoService.count(" ").open()).isEqualTo(4L);
    }

    // --- bulkUpdateStatus ---

    @Test
    void bulkUpdateStatus_delegatesToRepository_andReturnsCount() {
        List<String> ids = List.of("a", "b", "c");
        when(todoRepository.bulkSetStatus(eq(ids), eq(Todo.Status.ARCHIVED), any())).thenReturn(3);

        int updated = todoService.bulkUpdateStatus(ids, Todo.Status.ARCHIVED);

        assertThat(updated).isEqualTo(3);
        verify(todoRepository).bulkSetStatus(eq(ids), eq(Todo.Status.ARCHIVED), any());
    }

    @Test
    void bulkUpdateStatus_emptyIds_isNoOp() {
        assertThat(todoService.bulkUpdateStatus(List.of(), Todo.Status.ARCHIVED)).isZero();
        assertThat(todoService.bulkUpdateStatus(null, Todo.Status.ARCHIVED)).isZero();
        verify(todoRepository, never()).bulkSetStatus(any(), any(), any());
    }

    /** Modul goals aus (TODO c60bf914): der goalId-Filter ist ein 400 statt stillschweigend die ganze Liste. */
    @Test
    void findAll_goalFilterWhileGoalsIsOff_isRefused_withoutFilterItWorks() {
        org.mockito.Mockito.doReturn(false).when(modules).isEnabled(com.automation.modules.ModuleCatalog.GOALS);

        assertThatThrownBy(() -> todoService.findAll(0, 20, null, null, null, null, null, null, "g-1"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("goals");
        verify(todoRepository, never()).findAll(ArgumentMatchers.<Specification<Todo>>any(), any(Pageable.class));

        when(todoRepository.findAll(ArgumentMatchers.<Specification<Todo>>any(), any(Pageable.class))).thenReturn(Page.<Todo>empty());
        assertThat(todoService.findAll(0, 20, null, null, null, null, null, null, " ").content()).isEmpty();
    }
}
