package com.automation.service.todo;

import com.automation.dto.request.CreateTodoRequest;
import com.automation.dto.request.UpdateTodoRequest;
import com.automation.dto.request.UpdateTodoStatusRequest;
import com.automation.dto.response.TodoCountResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.TodoMapper;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TodoServiceTest {

    @Mock
    private TodoRepository todoRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private TodoMapper todoMapper;

    @InjectMocks
    private TodoService todoService;

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
                null, null, null, null, null, null, null);

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
                List.of("backend", "bug"), "proj-1", Todo.Source.AI, "claude-code",
                null, null, null, null);

        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        Todo result = todoService.create(request);

        assertThat(result.getTitle()).isEqualTo("Important");
        assertThat(result.getStatus()).isEqualTo(Todo.Status.IN_PROGRESS);
        assertThat(result.getPriority()).isEqualTo(Todo.Priority.HIGH);
        assertThat(result.getSource()).isEqualTo(Todo.Source.AI);
        assertThat(result.getSourceAgent()).isEqualTo("claude-code");
        assertThat(result.getProjectId()).isEqualTo("proj-1");
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
                "New Title", null, null, Todo.Priority.HIGH, null, null, null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getTitle()).isEqualTo("New Title");
        assertThat(result.getDescription()).isEqualTo("Old Desc");
        assertThat(result.getStatus()).isEqualTo(Todo.Status.OPEN);
        assertThat(result.getPriority()).isEqualTo(Todo.Priority.HIGH);
    }

    @Test
    void update_blankProjectId_setsToNull() {
        Todo existing = Todo.builder()
                .id("t-1").title("Test").projectId("proj-1")
                .build();
        when(todoRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(todoRepository.save(any(Todo.class))).thenAnswer(inv -> inv.getArgument(0));

        UpdateTodoRequest request = new UpdateTodoRequest(
                null, null, null, null, null, "", null, null);

        Todo result = todoService.update("t-1", request);

        assertThat(result.getProjectId()).isNull();
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
        when(todoRepository.countByStatus(Todo.Status.OPEN)).thenReturn(5L);
        when(todoRepository.countByStatus(Todo.Status.IN_PROGRESS)).thenReturn(3L);
        when(todoRepository.countByStatus(Todo.Status.DONE)).thenReturn(12L);
        when(todoRepository.countByStatus(Todo.Status.BACKLOG)).thenReturn(2L);

        TodoCountResponse counts = todoService.count();

        assertThat(counts.open()).isEqualTo(5L);
        assertThat(counts.inProgress()).isEqualTo(3L);
        assertThat(counts.done()).isEqualTo(12L);
        assertThat(counts.backlog()).isEqualTo(2L);
    }
}
