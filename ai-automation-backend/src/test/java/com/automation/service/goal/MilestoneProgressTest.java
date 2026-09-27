package com.automation.service.goal;

import com.automation.dto.projection.GoalMilestoneLinks;
import com.automation.dto.projection.GoalMilestoneSummaryRow;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalMilestone;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Zustand der Meilensteine aus ihren verknuepften Arbeitspaketen (TODO c60bf914 Ziele Stufe 1: Ziel →
 * Projekte → Arbeitspakete → TODOs, Meilensteine, Gliederung und Zeitstrahl): mit Paketen automatisch,
 * ohne Pakete von Hand, sonst nach Datum; reachedAt/reopened, Reihenfolge und der naechste Meilenstein.
 */
class MilestoneProgressTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");
    private static final Instant OLD = Instant.parse("2026-09-01T08:00:00Z");

    @Test
    void withPackages_reachedWhenAllAreCompleted_andTheFirstDetectionIsStamped() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(10)), null);

        GoalMilestoneResponse half = MilestoneProgress.toResponse(m, List.of("wp1", "wp2"), Set.of("wp1"), NOW);
        assertThat(half.state()).isEqualTo(GoalDueState.OPEN);
        assertThat(half.packagesTotal()).isEqualTo(2);
        assertThat(half.packagesDone()).isEqualTo(1);
        assertThat(m.getReachedAt()).isNull();

        GoalMilestoneResponse all = MilestoneProgress.toResponse(m, List.of("wp1", "wp2"), Set.of("wp1", "wp2", "other"), NOW);
        assertThat(all.state()).as("erreicht schlaegt jedes Datum").isEqualTo(GoalDueState.REACHED);
        assertThat(all.reachedAt()).isEqualTo(NOW);
        assertThat(m.getReachedAt()).as("an der Entity, die Schreib-Transaktion speichert es").isEqualTo(NOW);
        assertThat(all.workPackageIds()).containsExactly("wp1", "wp2");

        GoalMilestoneResponse again = MilestoneProgress.toResponse(m, List.of("wp1", "wp2"), Set.of("wp1"),
                NOW.plus(Duration.ofDays(20)));
        assertThat(again.state()).as("wieder offen: der Zustand folgt dem Datum").isEqualTo(GoalDueState.OVERDUE);
        assertThat(again.reopened()).isTrue();
        assertThat(again.reachedAt()).as("der erste Zeitpunkt bleibt").isEqualTo(NOW);
    }

    @Test
    void withoutPackages_onlyReachedByHand_andNeverReopened() {
        GoalMilestone open = milestone("open", NOW.minus(Duration.ofDays(1)), null);
        GoalMilestone manual = milestone("manual", NOW.minus(Duration.ofDays(1)), OLD);

        GoalMilestoneResponse overdue = MilestoneProgress.toResponse(open, List.of(), Set.of("wp1"), NOW);
        GoalMilestoneResponse reached = MilestoneProgress.toResponse(manual, List.of(), Set.of(), NOW);

        assertThat(overdue.state()).isEqualTo(GoalDueState.OVERDUE);
        assertThat(overdue.packagesTotal()).isZero();
        assertThat(open.getReachedAt()).as("ohne Pakete stempelt das Lesen nichts").isNull();
        assertThat(reached.state()).isEqualTo(GoalDueState.REACHED);
        assertThat(reached.reachedAt()).isEqualTo(OLD);
        assertThat(reached.reopened()).isFalse();
    }

    @Test
    void stateByDate_dueSoonWithinThreeDays() {
        assertThat(MilestoneProgress.toResponse(milestone("soon", NOW.plus(Duration.ofDays(3)), null), List.of(), Set.of(), NOW)
                .state()).isEqualTo(GoalDueState.DUE_SOON);
        assertThat(MilestoneProgress.toResponse(milestone("later", NOW.plus(Duration.ofDays(4)), null), List.of(), Set.of(), NOW)
                .state()).isEqualTo(GoalDueState.OPEN);
    }

    @Test
    void evaluate_sortsByDate_thenTitle_andNextSkipsReached() {
        List<GoalMilestoneLinks> milestones = List.of(
                new GoalMilestoneLinks(milestone("b", NOW.plus(Duration.ofDays(5)), null, "Beta"), List.of()),
                new GoalMilestoneLinks(milestone("r", NOW.plus(Duration.ofDays(1)), OLD, "Release-Check"), List.of()),
                new GoalMilestoneLinks(milestone("a", NOW.plus(Duration.ofDays(5)), null, "Alpha"), List.of("wp1")));

        List<GoalMilestoneResponse> evaluated = MilestoneProgress.evaluate(milestones, Set.of(), NOW);

        assertThat(evaluated).extracting(GoalMilestoneResponse::id).containsExactly("r", "a", "b");
        GoalResponse.NextMilestone next = MilestoneProgress.next(evaluated);
        assertThat(next).isEqualTo(new GoalResponse.NextMilestone("a", "Alpha", NOW.plus(Duration.ofDays(5)), GoalDueState.OPEN));
        assertThat(MilestoneProgress.evaluate(List.of(), Set.of(), NOW)).isEmpty();
        assertThat(MilestoneProgress.next(List.of())).isNull();
    }

    /** Die Zielliste rechnet aus Zusammenfassungen dasselbe aus wie die Detailansicht. */
    @Test
    void nextOf_summaries_sameRules_withoutLoadingPackages() {
        Instant soon = NOW.plus(Duration.ofDays(2));
        List<GoalMilestoneSummaryRow> rows = List.of(
                new GoalMilestoneSummaryRow("done", "g1", "Alles fertig", NOW.minus(Duration.ofDays(3)), null, 2, 2),
                new GoalMilestoneSummaryRow("manual", "g1", "Von Hand", NOW.minus(Duration.ofDays(2)), OLD, 0, 0),
                new GoalMilestoneSummaryRow("late", "g1", "Spaet", NOW.plus(Duration.ofDays(9)), null, 0, 0),
                new GoalMilestoneSummaryRow("reopened", "g1", "Wieder offen", soon, OLD, 2, 1));

        GoalResponse.NextMilestone next = MilestoneProgress.nextOf(rows, NOW);

        assertThat(next).isEqualTo(new GoalResponse.NextMilestone("reopened", "Wieder offen", soon, GoalDueState.DUE_SOON));
        assertThat(MilestoneProgress.nextOf(rows.subList(0, 2), NOW)).as("alles erreicht").isNull();
        assertThat(rows.get(0).reached()).isTrue();
        assertThat(rows.get(1).reached()).isTrue();
        assertThat(rows.get(3).reached()).as("mit Paketen zaehlen die Pakete, nicht reachedAt").isFalse();
    }

    private static GoalMilestone milestone(String id, Instant due, Instant reachedAt) {
        return milestone(id, due, reachedAt, id);
    }

    private static GoalMilestone milestone(String id, Instant due, Instant reachedAt, String title) {
        return GoalMilestone.builder().id(id).goalId("g1").title(title).dueDate(due).reachedAt(reachedAt)
                .createdAt(OLD).updatedAt(OLD).build();
    }
}
