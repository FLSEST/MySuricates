package com.automation.service.goal;

import com.automation.dto.projection.GuardScheduleRow;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.entity.Goal;
import com.automation.repository.ScheduledWorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/** Goal guard: schedule precedence, one query for the whole list, goals without a guard (TODO 4e185f05). */
@ExtendWith(MockitoExtension.class)
class GoalGuardLookupTest {

    private static final Instant OLDER = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant NEWER = Instant.parse("2026-09-20T10:00:00Z");
    private static final Instant NEXT_RUN = Instant.parse("2026-09-26T06:00:00Z");

    @Mock private ScheduledWorkflowRepository scheduleRepository;

    private GoalGuardLookup lookup;

    @BeforeEach
    void setUp() {
        lookup = new GoalGuardLookup(scheduleRepository);
    }

    @Test
    void byGoal_goalWithoutGuardWorkflow_isNone_withoutQuery() {
        Map<String, GoalGuardResponse> result = lookup.byGoal(List.of(goal("g1", null)));

        assertThat(result).containsOnly(entry("g1", GoalGuardResponse.NONE));
        verifyNoInteractions(scheduleRepository);
    }

    @Test
    void byGoal_noGoals_isEmpty_withoutQuery() {
        assertThat(lookup.byGoal(List.of())).isEmpty();
        verifyNoInteractions(scheduleRepository);
    }

    @Test
    void byGoal_guardWithoutSchedule_isOff_butKeepsItsWorkflow() {
        when(scheduleRepository.findGuardRows(List.of("wf-1"))).thenReturn(List.of());

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard).isEqualTo(new GoalGuardResponse(false, "wf-1", null, null));
    }

    @Test
    void byGoal_enabledSchedule_carriesItsNextRun() {
        when(scheduleRepository.findGuardRows(List.of("wf-1")))
                .thenReturn(List.of(schedule("wf-1", "s1", true, NEXT_RUN, OLDER)));

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard).isEqualTo(new GoalGuardResponse(true, "wf-1", "s1", NEXT_RUN));
    }

    @Test
    void byGoal_disabledSchedule_hasNoNextRun() {
        when(scheduleRepository.findGuardRows(List.of("wf-1")))
                .thenReturn(List.of(schedule("wf-1", "s1", false, NEXT_RUN, OLDER)));

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard).as("pausierter Zeitplan: kein naechster Lauf, auch wenn die Zeile noch einen traegt")
                .isEqualTo(new GoalGuardResponse(false, "wf-1", "s1", null));
    }

    @Test
    void byGoal_severalSchedules_activeBeatsNewest() {
        when(scheduleRepository.findGuardRows(List.of("wf-1"))).thenReturn(List.of(
                schedule("wf-1", "newer-off", false, NEXT_RUN.plusSeconds(60), NEWER),
                schedule("wf-1", "older-on", true, NEXT_RUN, OLDER)));

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard).isEqualTo(new GoalGuardResponse(true, "wf-1", "older-on", NEXT_RUN));
    }

    @Test
    void byGoal_severalActiveSchedules_newestWins_undatedLast() {
        when(scheduleRepository.findGuardRows(List.of("wf-1"))).thenReturn(List.of(
                schedule("wf-1", "undated", true, NEXT_RUN.plusSeconds(1), null),
                schedule("wf-1", "older", true, NEXT_RUN.plusSeconds(2), OLDER),
                schedule("wf-1", "newer", true, NEXT_RUN, NEWER)));

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard).as("Zeitplan und naechster Lauf stammen aus derselben Zeile")
                .isEqualTo(new GoalGuardResponse(true, "wf-1", "newer", NEXT_RUN));
    }

    @Test
    void byGoal_undatedSchedule_losesAgainstADatedOne() {
        when(scheduleRepository.findGuardRows(List.of("wf-1"))).thenReturn(List.of(
                schedule("wf-1", "undated", false, null, null),
                schedule("wf-1", "older", false, null, OLDER)));

        GoalGuardResponse guard = lookup.byGoal(List.of(goal("g1", "wf-1"))).get("g1");

        assertThat(guard.scheduleId()).as("null createdAt zuletzt, auch unter lauter pausierten").isEqualTo("older");
        assertThat(guard.enabled()).isFalse();
    }

    @Test
    void byGoal_goalsSharingAGuard_needOneQuery_withDistinctIds() {
        when(scheduleRepository.findGuardRows(List.of("wf-1", "wf-2", "wf-3"))).thenReturn(List.of(
                schedule("wf-1", "s1", true, NEXT_RUN, OLDER),
                schedule("wf-2", "s2", false, NEXT_RUN, NEWER)));

        Map<String, GoalGuardResponse> result = lookup.byGoal(List.of(
                goal("g1", "wf-1"), goal("g2", "wf-1"), goal("g3", null), goal("g4", "wf-2"), goal("g5", "wf-3")));

        assertThat(result).containsOnly(
                entry("g1", new GoalGuardResponse(true, "wf-1", "s1", NEXT_RUN)),
                entry("g2", new GoalGuardResponse(true, "wf-1", "s1", NEXT_RUN)),
                entry("g3", GoalGuardResponse.NONE),
                entry("g4", new GoalGuardResponse(false, "wf-2", "s2", null)),
                entry("g5", new GoalGuardResponse(false, "wf-3", null, null)));
        verify(scheduleRepository).findGuardRows(List.of("wf-1", "wf-2", "wf-3"));
        verifyNoMoreInteractions(scheduleRepository);
    }

    @Test
    void of_delegatesToTheBatch() {
        when(scheduleRepository.findGuardRows(List.of("wf-1")))
                .thenReturn(List.of(schedule("wf-1", "s1", true, NEXT_RUN, OLDER)));

        assertThat(lookup.of(goal("g1", "wf-1"))).isEqualTo(new GoalGuardResponse(true, "wf-1", "s1", NEXT_RUN));
        verify(scheduleRepository).findGuardRows(List.of("wf-1"));
        verifyNoMoreInteractions(scheduleRepository);
    }

    @Test
    void of_goalWithoutGuardWorkflow_isNone_withoutQuery() {
        assertThat(lookup.of(goal("g1", null))).isSameAs(GoalGuardResponse.NONE);
        verifyNoInteractions(scheduleRepository);
    }

    private static Goal goal(String id, String guardWorkflowId) {
        return Goal.builder().id(id).title(id).guardWorkflowId(guardWorkflowId).build();
    }

    private static GuardScheduleRow schedule(String workflowId, String scheduleId, boolean enabled,
                                             Instant nextRunAt, Instant createdAt) {
        return new GuardScheduleRow(workflowId, scheduleId, enabled, nextRunAt, createdAt);
    }
}
