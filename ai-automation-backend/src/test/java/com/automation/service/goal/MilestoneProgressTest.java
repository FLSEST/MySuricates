package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalMilestone.State;
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
 * Meilenstein-Zustand direkt aus den TODO-Zeilen, ohne Service drumherum (TODO c8a11b7a): Zaehlung,
 * Reihenfolge, Feldabbildung und die reachedAt-/reopened-Regel. Die Zustandsgrenzen selbst prueft
 * {@code GoalMilestoneServiceTest} ueber {@code list}.
 */
class MilestoneProgressTest {

    private static final Instant NOW = Instant.parse("2026-09-25T10:00:00Z");
    private static final Instant OLD = Instant.parse("2026-09-01T08:00:00Z");
    private static final Instant NEW = Instant.parse("2026-09-20T08:00:00Z");

    @Test
    void evaluate_withoutMilestones_isEmpty_whateverTheRows() {
        List<GoalTodoRow> rows = List.of(row("t1", Todo.Status.DONE, "m1"));

        assertThat(MilestoneProgress.evaluate(null, rows, NOW)).isEmpty();
        assertThat(MilestoneProgress.evaluate(List.of(), rows, NOW)).isEmpty();
    }

    @Test
    void evaluate_countsPerMilestone_skipsArchivedUnassignedAndForeignRows() {
        GoalMilestone m1 = milestone("m1", NOW.plus(Duration.ofDays(10)), null, null);
        GoalMilestone m2 = milestone("m2", NOW.plus(Duration.ofDays(20)), null, null);
        List<GoalTodoRow> rows = List.of(
                row("t1", Todo.Status.DONE, "m1"),
                row("t2", Todo.Status.IN_PROGRESS, "m1"),
                row("t3", Todo.Status.OPEN, "m1"),
                row("t4", Todo.Status.BACKLOG, "m1"),
                row("t5", Todo.Status.ARCHIVED, "m1"),
                row("t6", Todo.Status.DONE, null),
                row("t7", Todo.Status.DONE, "other-milestone"));

        Map<String, GoalMilestoneResponse> byId = byId(MilestoneProgress.evaluate(List.of(m1, m2), rows, NOW));

        assertThat(byId.get("m1").total()).as("alles ausser ARCHIVED zaehlt, BACKLOG eingeschlossen").isEqualTo(4);
        assertThat(byId.get("m1").done()).isEqualTo(1);
        assertThat(byId.get("m2").total()).as("TODOs ohne oder mit fremdem Meilenstein zaehlen nirgends").isZero();
        assertThat(byId.get("m2").done()).isZero();
        assertThat(byId.get("m2").state()).isEqualTo(State.OPEN);
    }

    @Test
    void evaluate_sortsByDueDate_undatedLast_tiesByCreation_uncreatedLast() {
        Instant tie = NOW.plus(Duration.ofDays(10));
        List<GoalMilestone> scrambled = List.of(
                milestone("undated-new", null, null, NEW),
                milestone("tie-uncreated", tie, null, null),
                milestone("late", NOW.plus(Duration.ofDays(20)), null, OLD),
                milestone("tie-new", tie, null, NEW),
                milestone("undated-old", null, null, OLD),
                milestone("early", NOW.plus(Duration.ofDays(5)), null, NEW),
                milestone("tie-old", tie, null, OLD));

        List<String> order = MilestoneProgress.evaluate(scrambled, List.of(), NOW).stream()
                .map(GoalMilestoneResponse::id).toList();

        assertThat(order).containsExactly(
                "early", "tie-old", "tie-new", "tie-uncreated", "late", "undated-old", "undated-new");
    }

    @Test
    void toResponse_carriesTheMilestoneFieldsAndCounts() {
        Instant due = NOW.plus(Duration.ofDays(10));
        GoalMilestone m = GoalMilestone.builder().id("m").goalId("g1").title("Beta")
                .dueDate(due).createdAt(OLD).updatedAt(NEW).build();

        GoalMilestoneResponse response = MilestoneProgress.toResponse(m, 3, 1, NOW);

        assertThat(response).isEqualTo(
                new GoalMilestoneResponse("m", "g1", "Beta", due, State.OPEN, 3, 1, null, false, OLD, NEW));
    }

    @Test
    void toResponse_firstReachStampsNow_onTheEntity_andKeepsIt() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(1)), null, OLD);

        GoalMilestoneResponse first = MilestoneProgress.toResponse(m, 2, 2, NOW);
        GoalMilestoneResponse later = MilestoneProgress.toResponse(m, 2, 2, NOW.plus(Duration.ofHours(5)));

        assertThat(first.state()).isEqualTo(State.REACHED);
        assertThat(m.getReachedAt()).as("an der Entity, die Schreib-Transaktion speichert es").isEqualTo(NOW);
        assertThat(first.reachedAt()).isEqualTo(NOW);
        assertThat(later.reachedAt()).as("der erste Zeitpunkt bleibt").isEqualTo(NOW);
        assertThat(first.reopened()).isFalse();
        assertThat(later.reopened()).isFalse();
    }

    @Test
    void toResponse_reopenedOnlyWhenReachedBefore() {
        GoalMilestone never = milestone("never", NOW.plus(Duration.ofDays(10)), null, OLD);
        GoalMilestone before = milestone("before", NOW.plus(Duration.ofDays(10)), NOW.minus(Duration.ofDays(1)), OLD);

        GoalMilestoneResponse open = MilestoneProgress.toResponse(never, 2, 1, NOW);
        GoalMilestoneResponse reopened = MilestoneProgress.toResponse(before, 3, 2, NOW);

        assertThat(open.reopened()).isFalse();
        assertThat(never.getReachedAt()).as("nicht erreicht: nichts gestempelt").isNull();
        assertThat(reopened.state()).isEqualTo(State.OPEN);
        assertThat(reopened.reopened()).isTrue();
        assertThat(reopened.reachedAt()).isEqualTo(NOW.minus(Duration.ofDays(1)));
    }

    private static GoalMilestone milestone(String id, Instant due, Instant reachedAt, Instant createdAt) {
        return GoalMilestone.builder().id(id).goalId("g1").title(id).dueDate(due).reachedAt(reachedAt)
                .createdAt(createdAt).build();
    }

    private static GoalTodoRow row(String id, Todo.Status status, String milestoneId) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                null, NOW, NOW, null, milestoneId);
    }

    private static Map<String, GoalMilestoneResponse> byId(List<GoalMilestoneResponse> list) {
        return list.stream().collect(Collectors.toMap(GoalMilestoneResponse::id, Function.identity()));
    }
}
