package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.entity.Goal;
import com.automation.entity.Todo;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Reihenfolge der TODOs eines Ziels (TODO dc0566f8) – gebuendelt geladen (TODO f83dcc48). */
@ExtendWith(MockitoExtension.class)
class GoalMembershipServiceTest {

    private static final String GOAL_ID = "g-1";

    @Mock private GoalService goalService;
    @Mock private TodoRepository todoRepository;

    private GoalMembershipService service;
    private Goal goal;

    @BeforeEach
    void setUp() {
        service = new GoalMembershipService(goalService, todoRepository);
        goal = Goal.builder().id(GOAL_ID).title("Open-Source-Launch").build();
        when(goalService.find(GOAL_ID)).thenReturn(goal);
    }

    /**
     * Die genannten TODOs bekommen Rang 1..n, die uebrigen verlieren ihren Rang. Die TODOs, deren Rang
     * wegfaellt, kommen in einer Abfrage – vorher lud reorder jedes einzeln per findById.
     */
    @Test
    void reorder_ranksTheNamed_clearsTheRest_andLoadsTheDroppedInOneQuery() {
        Todo first = todo("t-1", 3);
        Todo second = todo("t-2", null);
        Todo formerFirst = todo("t-3", 1);
        Todo formerSecond = todo("t-4", 2);
        Todo unranked = todo("t-5", null);
        when(todoRepository.findAllById(List.of("t-1", "t-2"))).thenReturn(List.of(first, second));
        when(todoRepository.findGoalRows(GOAL_ID)).thenReturn(List.of(
                row(first), row(second), row(formerFirst), row(formerSecond), row(unranked)));
        when(todoRepository.findAllById(List.of("t-3", "t-4"))).thenReturn(List.of(formerFirst, formerSecond));

        service.reorder(GOAL_ID, List.of("t-1", "t-2"));

        assertThat(first.getGoalRank()).isEqualTo(1);
        assertThat(second.getGoalRank()).isEqualTo(2);
        assertThat(formerFirst.getGoalRank()).isNull();
        assertThat(formerSecond.getGoalRank()).isNull();
        verify(todoRepository, times(2)).findAllById(any());
        verify(todoRepository, never()).findById(any());
        verify(todoRepository, times(4)).save(any());
        verify(todoRepository, never()).save(unranked);
        verify(goalService).detailOf(goal);
    }

    @Test
    void reorder_withoutDroppedRanks_needsNoSecondQuery() {
        Todo first = todo("t-1", 1);
        Todo second = todo("t-2", 2);
        Todo unranked = todo("t-3", null);
        when(todoRepository.findAllById(List.of("t-2", "t-1"))).thenReturn(List.of(first, second));
        when(todoRepository.findGoalRows(GOAL_ID)).thenReturn(List.of(row(first), row(second), row(unranked)));

        service.reorder(GOAL_ID, List.of("t-2", "t-1"));

        assertThat(second.getGoalRank()).isEqualTo(1);
        assertThat(first.getGoalRank()).isEqualTo(2);
        assertThat(unranked.getGoalRank()).isNull();
        verify(todoRepository, times(1)).findAllById(any());
        verify(todoRepository, times(2)).save(any());
    }

    @Test
    void reorder_rejectsATodoOfAnotherGoal() {
        Todo foreign = todo("t-9", null);
        foreign.setGoalId("g-other");
        when(todoRepository.findAllById(List.of("t-9"))).thenReturn(List.of(foreign));

        assertThatThrownBy(() -> service.reorder(GOAL_ID, List.of("t-9")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("t-9");
        verify(todoRepository, never()).save(any());
    }

    private static Todo todo(String id, Integer rank) {
        return Todo.builder().id(id).title("TODO " + id).status(Todo.Status.OPEN)
                .priority(Todo.Priority.MEDIUM).goalId(GOAL_ID).goalRank(rank).build();
    }

    /** Die schlanke Zeile, wie {@code findGoalRows} sie liefert – hier zaehlen nur Id und Rang. */
    private static GoalTodoRow row(Todo todo) {
        return new GoalTodoRow(todo.getId(), todo.getTitle(), todo.getStatus(), todo.getPriority(), null, null,
                todo.getGoalRank(), null, null, null, null, null, null, null, null, null, null);
    }
}
