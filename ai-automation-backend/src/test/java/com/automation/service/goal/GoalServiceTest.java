package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Reine Logik der Ziele (TODO dc0566f8): Arbeitsreihenfolge, Fortschritt, Eingaben. */
class GoalServiceTest {

    @Test
    void workOrder_rankFirst_thenPriority_thenAge_unrankedLast() {
        GoalTodoRow unrankedCritical = row("c", null, Todo.Priority.CRITICAL, Todo.Status.OPEN, 1);
        GoalTodoRow rank2 = row("r2", 2, Todo.Priority.LOW, Todo.Status.OPEN, 2);
        GoalTodoRow rank1 = row("r1", 1, Todo.Priority.LOW, Todo.Status.OPEN, 3);
        GoalTodoRow unrankedLowOld = row("lo", null, Todo.Priority.LOW, Todo.Status.OPEN, 0);
        GoalTodoRow unrankedLowNew = row("ln", null, Todo.Priority.LOW, Todo.Status.OPEN, 9);

        List<String> order = List.of(unrankedLowNew, rank2, unrankedCritical, unrankedLowOld, rank1).stream()
                .sorted(GoalService.WORK_ORDER).map(GoalTodoRow::id).toList();

        assertThat(order).containsExactly("r1", "r2", "c", "lo", "ln");
    }

    @Test
    void progress_ignoresArchived_countsOnlyActiveOpenQuestions() {
        List<GoalTodoRow> rows = List.of(
                row("a", null, Todo.Priority.LOW, Todo.Status.DONE, 0),
                row("b", null, Todo.Priority.LOW, Todo.Status.OPEN, 0, "Welcher Stil?"),
                row("c", null, Todo.Priority.LOW, Todo.Status.DONE, 0, "schon geklaert, aber Text blieb"),
                row("d", null, Todo.Priority.LOW, Todo.Status.ARCHIVED, 0),
                row("e", null, Todo.Priority.LOW, Todo.Status.IN_PROGRESS, 0));

        GoalProgressResponse progress = GoalService.progressOf(rows);

        assertThat(progress.total()).isEqualTo(4);
        assertThat(progress.done()).isEqualTo(2);
        assertThat(progress.percentDone()).isEqualTo(50);
        assertThat(progress.openQuestions()).isEqualTo(1);
    }

    @Test
    void progress_ofEmptyGoal_isZeroNotDivisionByZero() {
        assertThat(GoalProgressResponse.of(0, 0, 0, 0, 0).percentDone()).isZero();
        assertThat(GoalService.progressOf((com.automation.dto.projection.GoalStatusCounts) null).total()).isZero();
    }

    @Test
    void parseDate_acceptsDateAndInstant_rejectsGarbage() {
        assertThat(GoalService.parseDate("2026-10-15")).isEqualTo(Instant.parse("2026-10-15T00:00:00Z"));
        assertThat(GoalService.parseDate("2026-10-15T08:30:00Z")).isEqualTo(Instant.parse("2026-10-15T08:30:00Z"));
        assertThat(GoalService.parseDate(" ")).isNull();
        assertThatThrownBy(() -> GoalService.parseDate("morgen")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void planningValues_areNormalised_orRejected() {
        assertThat(TodoPlanningService.parseComplexity("xl")).isEqualTo(Todo.Complexity.XL);
        assertThat(TodoPlanningService.parseComplexity("")).isNull();
        assertThat(TodoPlanningService.parseComplexity(" m ")).isEqualTo(Todo.Complexity.M);
        assertThatThrownBy(() -> TodoPlanningService.parseComplexity("huge")).isInstanceOf(IllegalArgumentException.class)
                .hasMessage("complexity muss S, M, L oder XL sein, nicht 'huge'");

        assertThat(TodoPlanningService.parseEffort("XHIGH")).isEqualTo("xhigh");
        assertThat(TodoPlanningService.parseEffort(" ")).isNull();
        assertThatThrownBy(() -> TodoPlanningService.parseEffort("max")).isInstanceOf(IllegalArgumentException.class);

        assertThat(TodoPlanningService.text("  ", "openQuestion")).isNull();
        assertThatThrownBy(() -> TodoPlanningService.text("x".repeat(TodoPlanningService.MAX_TEXT + 1), "ratingNote"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static GoalTodoRow row(String id, Integer rank, Todo.Priority priority, Todo.Status status, int ageSeconds) {
        return row(id, rank, priority, status, ageSeconds, null);
    }

    private static GoalTodoRow row(String id, Integer rank, Todo.Priority priority, Todo.Status status, int createdSecond,
                                   String openQuestion) {
        Instant created = Instant.parse("2026-09-01T00:00:00Z").plusSeconds(createdSecond);
        return new GoalTodoRow(id, id, status, priority, null, null, rank, null, null, null, null, null,
                openQuestion, created, created, null, null);
    }
}
