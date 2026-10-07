package com.automation.service.goal;

import com.automation.dto.response.PageResponse;
import com.automation.dto.response.TodoResponse;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.TodoResponseAssembler;
import com.automation.service.todo.TodoSpecifications;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GoalCandidateServiceTest {

    private final GoalRepository goals = mock(GoalRepository.class);
    private final TodoRepository todos = mock(TodoRepository.class);
    private final TodoResponseAssembler assembler = mock(TodoResponseAssembler.class);
    private final GoalCandidateService service = new GoalCandidateService(goals, todos, assembler);

    private final Todo todo = Todo.builder().id("t-1").title("Candidate").build();
    private final List<TodoResponse> responses = List.of(mock(TodoResponse.class));

    @BeforeEach
    void setUp() {
        when(goals.existsById("g-1")).thenReturn(true);
        when(assembler.toResponses(List.of(todo))).thenReturn(responses);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsAPageOfOpenTodosWithoutGoalFromTheGoalsProjects() {
        when(todos.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(todo), PageRequest.of(1, 5), 11));

        try (MockedStatic<TodoSpecifications> specs = mockStatic(TodoSpecifications.class, CALLS_REAL_METHODS)) {
            PageResponse<TodoResponse> page = service.candidates("g-1", "login", "ux", false, 1, 5);

            assertThat(page.content()).isSameAs(responses);
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(5);
            assertThat(page.totalElements()).isEqualTo(11);
            assertThat(page.totalPages()).isEqualTo(3);
            specs.verify(() -> TodoSpecifications.listSpec(null, GoalCandidateService.OPEN, null, null, "ux", "login"));
            specs.verify(() -> TodoSpecifications.inGoal(TodoSpecifications.NO_GOAL));
            specs.verify(() -> TodoSpecifications.inProjectsOfGoal("g-1"));
        }
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(todos).findAll(any(Specification.class), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(5);
        assertThat(pageable.getValue().getSort().isUnsorted()).as("the order lives in the specification").isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allProjectsDropsTheProjectFilter() {
        when(todos.findAll(any(Specification.class), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(todo), PageRequest.of(0, 20), 1));

        try (MockedStatic<TodoSpecifications> specs = mockStatic(TodoSpecifications.class, CALLS_REAL_METHODS)) {
            PageResponse<TodoResponse> page = service.candidates("g-1", null, null, true, 0, 20);

            assertThat(page.totalElements()).isEqualTo(1);
            specs.verify(() -> TodoSpecifications.inGoal(TodoSpecifications.NO_GOAL));
            specs.verify(() -> TodoSpecifications.inProjectsOfGoal(anyString()), never());
        }
    }

    @Test
    void openMeansOpenInProgressAndBacklog() {
        assertThat(GoalCandidateService.OPEN).containsExactly("OPEN", "IN_PROGRESS", "BACKLOG");
    }

    @Test
    void unknownGoalIsANotFoundWithoutQueryingTodos() {
        assertThatThrownBy(() -> service.candidates("ghost", null, null, false, 0, 20))
                .isInstanceOf(ResourceNotFoundException.class);
        verifyNoInteractions(todos, assembler);
    }
}
