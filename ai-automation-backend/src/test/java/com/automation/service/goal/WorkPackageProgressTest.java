package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Arbeitspaket-Zustand direkt aus den TODO-Zeilen, ohne Service drumherum (TODO c8a11b7a, c60bf914):
 * Zaehlung, Reihenfolge, Feldabbildung und die completedAt-/reopened-Regel. Die Zustandsgrenzen selbst
 * prueft {@code GoalWorkPackageServiceTest} ueber {@code list}.
 */
class WorkPackageProgressTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant OLD = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant NEW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void evaluate_withoutWorkPackages_isEmpty_whateverTheRows() {
        List<GoalTodoRow> rows = List.of(row("t1", Todo.Status.DONE, "m1"));

        assertThat(WorkPackageProgress.evaluate(null, rows, NOW)).isEmpty();
        assertThat(WorkPackageProgress.evaluate(List.of(), rows, NOW)).isEmpty();
    }

    @Test
    void evaluate_countsPerWorkPackage_skipsArchivedUnassignedAndForeignRows() {
        GoalWorkPackage m1 = workPackage("m1", NOW.plus(Duration.ofDays(10)), null, null);
        GoalWorkPackage m2 = workPackage("m2", NOW.plus(Duration.ofDays(20)), null, null);
        List<GoalTodoRow> rows = List.of(
                row("t1", Todo.Status.DONE, "m1"),
                row("t2", Todo.Status.IN_PROGRESS, "m1"),
                row("t3", Todo.Status.OPEN, "m1"),
                row("t4", Todo.Status.BACKLOG, "m1"),
                row("t5", Todo.Status.ARCHIVED, "m1"),
                row("t6", Todo.Status.DONE, null),
                row("t7", Todo.Status.DONE, "other-package"));

        Map<String, GoalWorkPackageResponse> byId = byId(WorkPackageProgress.evaluate(List.of(m1, m2), rows, NOW));

        assertThat(byId.get("m1").total()).as("alles ausser ARCHIVED zaehlt, BACKLOG eingeschlossen").isEqualTo(4);
        assertThat(byId.get("m1").done()).isEqualTo(1);
        assertThat(byId.get("m2").total()).as("TODOs ohne oder mit fremdem Paket zaehlen nirgends").isZero();
        assertThat(byId.get("m2").done()).isZero();
        assertThat(byId.get("m2").state()).isEqualTo(GoalDueState.OPEN);
    }

    @Test
    void evaluate_sortsByEnd_withoutPeriodLast_tiesByCreation_uncreatedLast() {
        Instant tie = NOW.plus(Duration.ofDays(10));
        List<GoalWorkPackage> scrambled = List.of(
                workPackage("undated-new", null, null, NEW),
                workPackage("tie-uncreated", tie, null, null),
                workPackage("late", NOW.plus(Duration.ofDays(20)), null, OLD),
                workPackage("tie-new", tie, null, NEW),
                workPackage("undated-old", null, null, OLD),
                workPackage("early", NOW.plus(Duration.ofDays(5)), null, NEW),
                workPackage("tie-old", tie, null, OLD));

        List<String> order = WorkPackageProgress.evaluate(scrambled, List.of(), NOW).stream()
                .map(GoalWorkPackageResponse::id).toList();

        assertThat(order).containsExactly(
                "early", "tie-old", "tie-new", "tie-uncreated", "late", "undated-old", "undated-new");
    }

    @Test
    void toResponse_carriesTheWorkPackageFieldsAndCounts() {
        Instant due = NOW.plus(Duration.ofDays(10));
        GoalWorkPackage m = GoalWorkPackage.builder().id("m").goalId("g1").title("Beta")
                .startDate(due).endDate(due).createdAt(OLD).updatedAt(NEW).build();

        GoalWorkPackageResponse response = WorkPackageProgress.toResponse(m, 3, 1, 0, NOW);

        assertThat(response).isEqualTo(
                new GoalWorkPackageResponse("m", "g1", null, "Beta", due, due, 0, GoalDueState.OPEN, 3, 1, 0, null, false, OLD, NEW, 0));
    }

    @Test
    void evaluate_sortsByProjectPosition_thenPositionInTheProject_unassignedProjectsLast() {
        List<GoalWorkPackage> scrambled = List.of(
                inProject("b-1", "pB", 1, NOW.plus(Duration.ofDays(1))),
                inProject("legacy", null, 0, NOW.plus(Duration.ofDays(1))),
                inProject("a-1", "pA", 1, NOW.plus(Duration.ofDays(2))),
                inProject("foreign", "pX", 0, NOW),
                inProject("b-0", "pB", 0, null),
                inProject("a-0", "pA", 0, NOW.plus(Duration.ofDays(30))));

        List<String> order = WorkPackageProgress.evaluate(scrambled, List.of(), Map.of("pB", 0, "pA", 1), NOW).stream()
                .map(GoalWorkPackageResponse::id).toList();

        assertThat(order).as("Projekt-Position schlaegt Datum, Position im Projekt schlaegt Datum")
                .containsExactly("b-0", "b-1", "a-0", "a-1", "foreign", "legacy");
    }

    @Test
    void evaluate_countsOpenQuestionsOfActiveTodosPerWorkPackage() {
        GoalWorkPackage m = workPackage("m1", null, null, OLD);
        List<GoalTodoRow> rows = List.of(
                question("t1", Todo.Status.OPEN, "m1", "Welcher Stil?"),
                question("t2", Todo.Status.BACKLOG, "m1", "Wer?"),
                question("t3", Todo.Status.DONE, "m1", "geklaert, Text blieb"),
                question("t4", Todo.Status.ARCHIVED, "m1", "verworfen"),
                question("t5", Todo.Status.OPEN, "m1", null),
                question("t6", Todo.Status.OPEN, null, "ohne Paket"));

        GoalWorkPackageResponse response = WorkPackageProgress.evaluate(List.of(m), rows, NOW).get(0);

        assertThat(response.openQuestions()).isEqualTo(2);
        assertThat(response.total()).isEqualTo(4);
    }

    @Test
    void withoutPeriod_isNeverDueSoonOrOverdue_onlyOpenOrReached() {
        GoalWorkPackage undated = workPackage("u", null, null, OLD);

        assertThat(WorkPackageProgress.toResponse(undated, 3, 1, 0, NOW).state()).isEqualTo(GoalDueState.OPEN);
        assertThat(WorkPackageProgress.toResponse(undated, 0, 0, 0, NOW.plus(Duration.ofDays(3650))).state()).isEqualTo(GoalDueState.OPEN);
        assertThat(WorkPackageProgress.toResponse(undated, 2, 2, 0, NOW).state()).isEqualTo(GoalDueState.REACHED);
        assertThat(WorkPackageProgress.toResponse(undated, 2, 2, 0, NOW).endDate()).isNull();
    }

    @Test
    void toResponse_carriesProjectAndPosition() {
        GoalWorkPackageResponse response = WorkPackageProgress.toResponse(inProject("m", "pA", 4, null), 0, 0, 1, NOW);

        assertThat(response.projectId()).isEqualTo("pA");
        assertThat(response.position()).isEqualTo(4);
        assertThat(response.openQuestions()).isEqualTo(1);
    }

    @Test
    void toResponse_firstReachStampsNow_onTheEntity_andKeepsIt() {
        GoalWorkPackage m = workPackage("m", NOW.plus(Duration.ofDays(1)), null, OLD);

        GoalWorkPackageResponse first = WorkPackageProgress.toResponse(m, 2, 2, 0, NOW);
        GoalWorkPackageResponse later = WorkPackageProgress.toResponse(m, 2, 2, 0, NOW.plus(Duration.ofHours(5)));

        assertThat(first.state()).isEqualTo(GoalDueState.REACHED);
        assertThat(m.getCompletedAt()).as("an der Entity, die Schreib-Transaktion speichert es").isEqualTo(NOW);
        assertThat(first.completedAt()).isEqualTo(NOW);
        assertThat(later.completedAt()).as("der erste Zeitpunkt bleibt").isEqualTo(NOW);
        assertThat(first.reopened()).isFalse();
        assertThat(later.reopened()).isFalse();
    }

    @Test
    void toResponse_reopenedOnlyWhenReachedBefore() {
        GoalWorkPackage never = workPackage("never", NOW.plus(Duration.ofDays(10)), null, OLD);
        GoalWorkPackage before = workPackage("before", NOW.plus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)), OLD);

        GoalWorkPackageResponse open = WorkPackageProgress.toResponse(never, 2, 1, 0, NOW);
        GoalWorkPackageResponse reopened = WorkPackageProgress.toResponse(before, 3, 2, 0, NOW);

        assertThat(open.reopened()).isFalse();
        assertThat(never.getCompletedAt()).as("nicht erreicht: nichts gestempelt").isNull();
        assertThat(reopened.state()).isEqualTo(GoalDueState.OPEN);
        assertThat(reopened.reopened()).isTrue();
        assertThat(reopened.completedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    /** Ein Paket mit Ende {@code end} (Start = Ende) oder ohne Zeitraum ({@code end} null). */
    private static GoalWorkPackage workPackage(String id, Instant end, Instant completedAt, Instant createdAt) {
        return GoalWorkPackage.builder().id(id).goalId("g1").title(id).startDate(end).endDate(end).completedAt(completedAt)
                .createdAt(createdAt).build();
    }

    private static GoalWorkPackage inProject(String id, String projectId, int position, Instant end) {
        return GoalWorkPackage.builder().id(id).goalId("g1").title(id).projectId(projectId).position(position)
                .startDate(end).endDate(end).createdAt(OLD).build();
    }

    /** TODO 68a5e24e: entschiedene TODOs je Paket, ohne ARCHIVED und ohne solche mit neuer Frage. */
    @Test
    void evaluate_countsDecidedTodosPerWorkPackage() {
        GoalWorkPackage p = workPackage("p1", null, null, OLD);
        List<GoalTodoRow> rows = List.of(
                withDecision("t1", Todo.Status.DONE, null, "Ja"),
                withDecision("t2", Todo.Status.OPEN, "Neu?", "Alt"),
                withDecision("t3", Todo.Status.ARCHIVED, null, "Weg"),
                withDecision("t4", Todo.Status.OPEN, null, null));

        assertThat(WorkPackageProgress.evaluate(List.of(p), rows, NOW).get(0).decided()).isEqualTo(1);
    }

    private static GoalTodoRow withDecision(String id, Todo.Status status, String openQuestion, String decision) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                openQuestion, NOW, NOW, null, "p1", decision, null, null);
    }

    private static GoalTodoRow question(String id, Todo.Status status, String workPackageId, String openQuestion) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                openQuestion, NOW, NOW, null, workPackageId);
    }

    private static GoalTodoRow row(String id, Todo.Status status, String workPackageId) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                null, NOW, NOW, null, workPackageId);
    }

    private static Map<String, GoalWorkPackageResponse> byId(List<GoalWorkPackageResponse> list) {
        return list.stream().collect(Collectors.toMap(GoalWorkPackageResponse::id, Function.identity()));
    }
}
