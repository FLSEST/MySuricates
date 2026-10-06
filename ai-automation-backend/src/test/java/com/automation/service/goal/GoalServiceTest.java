package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.dto.response.GoalResponse;
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

    /** TODO c60bf914: goal projects with counters from the non-archived TODOs in this project. */
    @Test
    void projects_countPerAssignedProject_inTheirOrder_withoutArchived() {
        List<GoalProjectRow> projects = List.of(
                new GoalProjectRow("g1", "pB", "Site", null, 0),
                new GoalProjectRow("g1", "pA", "AutoPilot", Instant.parse("2026-09-01T00:00:00Z"), 1),
                new GoalProjectRow("g1", "pEmpty", "Leer", null, 2));
        List<GoalTodoRow> rows = List.of(
                inProject("a1", "pA", Todo.Status.DONE, null), inProject("a2", "pA", Todo.Status.OPEN, "Wer?"),
                inProject("a3", "pA", Todo.Status.ARCHIVED, "verworfen"), inProject("a4", "pA", Todo.Status.DONE, "alt"),
                inProject("b1", "pB", Todo.Status.BACKLOG, "Welcher Stil?"),
                inProject("x1", "pOther", Todo.Status.OPEN, null), inProject("n1", null, Todo.Status.OPEN, null));

        List<GoalDetailResponse.ProjectSummary> summaries = GoalProjectSummaries.projects(projects, rows);

        assertThat(summaries).containsExactly(
                new GoalDetailResponse.ProjectSummary("pB", "Site", false, 0, 1, 0, 1, 0),
                new GoalDetailResponse.ProjectSummary("pA", "AutoPilot", true, 1, 3, 2, 1, 0),
                new GoalDetailResponse.ProjectSummary("pEmpty", "Leer", false, 2, 0, 0, 0, 0));
        assertThat(GoalProjectSummaries.refs(projects)).extracting(GoalResponse.ProjectRef::id)
                .containsExactly("pB", "pA", "pEmpty");
    }

    @Test
    void refsByGoal_groupsTheRowsOfSeveralGoals_keepingTheirOrder() {
        var byGoal = GoalProjectSummaries.refsByGoal(List.of(
                new GoalProjectRow("g1", "pB", "Site", null, 0), new GoalProjectRow("g1", "pA", "AutoPilot", null, 1),
                new GoalProjectRow("g2", "pA", "AutoPilot", null, 0)));

        assertThat(byGoal.get("g1")).containsExactly(new GoalResponse.ProjectRef("pB", "Site"),
                new GoalResponse.ProjectRef("pA", "AutoPilot"));
        assertThat(byGoal.get("g2")).containsExactly(new GoalResponse.ProjectRef("pA", "AutoPilot"));
    }

    /** TODO 68a5e24e: decided = decision without an open question, not archived – in progress and per project. */
    @Test
    void decided_countsDecisionsWithoutOpenQuestion_withoutArchived() {
        List<GoalTodoRow> rows = List.of(
                decided("d1", "pA", Todo.Status.DONE, null, "Ja"),
                decided("d2", "pA", Todo.Status.OPEN, null, "Nein"),
                decided("q1", "pA", Todo.Status.OPEN, "Wieder offen?", "Frueher: ja"),
                decided("a1", "pA", Todo.Status.ARCHIVED, null, "Verworfen"),
                decided("n1", "pA", Todo.Status.OPEN, null, null));

        assertThat(GoalService.progressOf(rows).decided()).isEqualTo(2);
        assertThat(GoalProjectSummaries.projects(List.of(new GoalProjectRow("g1", "pA", "A", null, 0)), rows))
                .singleElement().extracting(GoalDetailResponse.ProjectSummary::decided).isEqualTo(2L);
        assertThat(rows.get(2).decisionState()).isEqualTo(Todo.DecisionState.QUESTION);
        assertThat(rows.get(4).decisionState()).isEqualTo(Todo.DecisionState.NONE);
    }

    private static GoalTodoRow decided(String id, String projectId, Todo.Status status, String openQuestion, String decision) {
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, projectId, null, null, null, null, null, null, null,
                openQuestion, created, created, null, "wp1", decision, decision != null ? created : null,
                decision != null ? Todo.RatedBy.USER : null);
    }

    private static GoalTodoRow inProject(String id, String projectId, Todo.Status status, String openQuestion) {
        Instant created = Instant.parse("2026-09-01T00:00:00Z");
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, projectId, null, null, null, null, null, null, null,
                openQuestion, created, created, null, null);
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
