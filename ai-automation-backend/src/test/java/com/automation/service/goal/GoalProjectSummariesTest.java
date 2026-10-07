package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse.ProjectProgress;
import com.automation.dto.response.GoalDetailResponse.ProjectSummary;
import com.automation.dto.response.GoalResponse.ProjectRef;
import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class GoalProjectSummariesTest {

    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");

    private static GoalTodoRow row(String id, String projectId, Todo.Status status, String openQuestion, String decision) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, projectId, null, null, null, null, null, null,
                null, openQuestion, CREATED, CREATED, null, null, decision, null, null);
    }

    private static GoalTodoRow row(String id, String projectId, Todo.Status status) {
        return row(id, projectId, status, null, null);
    }

    @Test
    void byProject_countsTotalAndDonePerProjectInFirstSeenOrder_withoutArchived() {
        List<GoalTodoRow> rows = List.of(
                row("b1", "pB", Todo.Status.OPEN),
                row("a1", "pA", Todo.Status.DONE),
                row("b2", "pB", Todo.Status.DONE),
                row("b3", "pB", Todo.Status.BACKLOG),
                row("a2", "pA", Todo.Status.ARCHIVED),
                row("x1", "pX", Todo.Status.ARCHIVED));

        List<ProjectProgress> progress = GoalProjectSummaries.byProject(rows, Map.of("pA", "Alpha", "pB", "Beta"));

        assertThat(progress).containsExactly(
                new ProjectProgress("pB", "Beta", 3, 1),
                new ProjectProgress("pA", "Alpha", 1, 1));
    }

    @Test
    void byProject_groupsTodosWithoutProjectUnderNull_andLeavesUnknownNamesNull() {
        Map<String, String> names = new HashMap<>();
        names.put(null, "never used as a project name");
        List<GoalTodoRow> rows = List.of(
                row("n1", null, Todo.Status.OPEN),
                row("n2", null, Todo.Status.DONE),
                row("u1", "pUnknown", Todo.Status.IN_PROGRESS));

        // a HashMap like the caller's: TODOs without a project look up their name with a null key
        List<ProjectProgress> progress = GoalProjectSummaries.byProject(rows, new HashMap<>());

        assertThat(progress).containsExactly(
                new ProjectProgress(null, null, 2, 1),
                new ProjectProgress("pUnknown", null, 1, 0));
        assertThat(GoalProjectSummaries.byProject(List.of(row("n1", null, Todo.Status.OPEN)), names))
                .singleElement().extracting(ProjectProgress::projectName).isEqualTo("never used as a project name");
    }

    @Test
    void byProject_ofNoRowsIsEmpty() {
        assertThat(GoalProjectSummaries.byProject(List.of(), Map.of())).isEmpty();
    }

    @Test
    void projects_countOpenQuestionsOnlyOnActiveTodosAndDecisionsOnlyWithoutQuestion() {
        List<GoalProjectRow> projects = List.of(new GoalProjectRow("g", "pA", "Alpha", null, 3));
        List<GoalTodoRow> rows = List.of(
                row("q-open", "pA", Todo.Status.OPEN, "Which?", null),
                row("q-progress", "pA", Todo.Status.IN_PROGRESS, "Which?", null),
                row("q-backlog", "pA", Todo.Status.BACKLOG, "Which?", null),
                row("q-done", "pA", Todo.Status.DONE, "Which?", null),
                row("decided", "pA", Todo.Status.DONE, null, "Yes"),
                row("reopened", "pA", Todo.Status.OPEN, "Again?", "Earlier: yes"),
                row("no-project", null, Todo.Status.OPEN, "Which?", "x"));

        assertThat(GoalProjectSummaries.projects(projects, rows))
                .containsExactly(new ProjectSummary("pA", "Alpha", false, 3, 6, 2, 4, 1));
    }

    @Test
    void projects_withoutRowsAreZero_andTheProjectOrderIsKept() {
        List<GoalProjectRow> projects = List.of(
                new GoalProjectRow("g", "p2", "Two", CREATED, 0),
                new GoalProjectRow("g", "p1", "One", null, 1));

        assertThat(GoalProjectSummaries.projects(projects, List.of())).containsExactly(
                new ProjectSummary("p2", "Two", true, 0, 0, 0, 0, 0),
                new ProjectSummary("p1", "One", false, 1, 0, 0, 0, 0));
        assertThat(GoalProjectSummaries.projects(List.of(), List.of(row("a", "p1", Todo.Status.OPEN)))).isEmpty();
    }

    @Test
    void refs_andRefsByGoal_keepTheRowOrder() {
        List<GoalProjectRow> rows = List.of(
                new GoalProjectRow("g2", "pC", "C", null, 0),
                new GoalProjectRow("g1", "pB", "B", null, 0),
                new GoalProjectRow("g2", "pA", "A", null, 1));

        assertThat(GoalProjectSummaries.refs(rows)).containsExactly(
                new ProjectRef("pC", "C"), new ProjectRef("pB", "B"), new ProjectRef("pA", "A"));
        Map<String, List<ProjectRef>> byGoal = GoalProjectSummaries.refsByGoal(rows);
        assertThat(byGoal.keySet()).containsExactly("g2", "g1");
        assertThat(byGoal.get("g2")).containsExactly(new ProjectRef("pC", "C"), new ProjectRef("pA", "A"));
        assertThat(GoalProjectSummaries.refsByGoal(List.of())).isEmpty();
    }
}
