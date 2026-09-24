package com.automation.service.goal;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithMilestones;
import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalMilestone.State;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Meilensteine: Zustaende, die reachedAt-Regel und die Pflege (TODO 259f4f66). */
@ExtendWith(MockitoExtension.class)
class GoalMilestoneServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Mock private GoalRepository goalRepository;
    @Mock private GoalMilestoneRepository milestoneRepository;
    @Mock private TodoRepository todoRepository;

    private GoalMilestoneService service;

    @BeforeEach
    void setUp() {
        service = new GoalMilestoneService(goalRepository, milestoneRepository, todoRepository,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void list_computesEachState_fromDueDateAndAssignedTodos() {
        GoalMilestone open = milestone("open", NOW.plus(Duration.ofDays(10)), null);
        GoalMilestone soon = milestone("soon", NOW.plus(Duration.ofDays(3)), null);
        GoalMilestone overdue = milestone("overdue", NOW.minus(Duration.ofHours(1)), null);
        GoalMilestone reached = milestone("reached", NOW.minus(Duration.ofDays(5)), null);
        goalWith(open, soon, overdue, reached);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "open"), row("t2", Todo.Status.OPEN, "open"),
                row("t3", Todo.Status.IN_PROGRESS, "overdue"),
                row("t4", Todo.Status.DONE, "reached"), row("t5", Todo.Status.DONE, "reached"),
                row("t6", Todo.Status.OPEN, null)));

        Map<String, GoalMilestoneResponse> byId = byId(service.list("g1"));

        assertThat(byId.get("open").state()).isEqualTo(State.OPEN);
        assertThat(byId.get("open").total()).isEqualTo(2);
        assertThat(byId.get("open").done()).isEqualTo(1);
        assertThat(byId.get("soon").state()).as("genau drei Tage vorher: bald faellig").isEqualTo(State.DUE_SOON);
        assertThat(byId.get("soon").total()).isZero();
        assertThat(byId.get("overdue").state()).isEqualTo(State.OVERDUE);
        assertThat(byId.get("reached").state()).as("erreicht schlaegt ueberfaellig").isEqualTo(State.REACHED);
        assertThat(byId.get("reached").reopened()).isFalse();
    }

    @Test
    void list_emptyMilestoneIsNeverReached_andArchivedTodosDoNotCount() {
        GoalMilestone empty = milestone("empty", NOW.plus(Duration.ofDays(30)), null);
        GoalMilestone onlyArchived = milestone("archived", NOW.plus(Duration.ofDays(30)), null);
        GoalMilestone doneAndArchived = milestone("mixed", NOW.plus(Duration.ofDays(30)), null);
        goalWith(empty, onlyArchived, doneAndArchived);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.ARCHIVED, "archived"),
                row("t2", Todo.Status.DONE, "mixed"), row("t3", Todo.Status.ARCHIVED, "mixed")));

        Map<String, GoalMilestoneResponse> byId = byId(service.list("g1"));

        assertThat(byId.get("empty").state()).isEqualTo(State.OPEN);
        assertThat(byId.get("archived").state()).isEqualTo(State.OPEN);
        assertThat(byId.get("archived").total()).isZero();
        assertThat(byId.get("mixed").state()).isEqualTo(State.REACHED);
        assertThat(byId.get("mixed").total()).isEqualTo(1);
    }

    @Test
    void list_firstDetectionSetsReachedAt_andKeepsAnEarlierOne() {
        Instant earlier = NOW.minus(Duration.ofDays(2));
        GoalMilestone fresh = milestone("fresh", NOW.plus(Duration.ofDays(9)), null);
        GoalMilestone known = milestone("known", NOW.plus(Duration.ofDays(9)), earlier);
        goalWith(fresh, known);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "fresh"), row("t2", Todo.Status.DONE, "known")));

        Map<String, GoalMilestoneResponse> byId = byId(service.list("g1"));

        assertThat(fresh.getReachedAt()).as("an der Entity gesetzt, die Transaktion speichert").isEqualTo(NOW);
        assertThat(byId.get("fresh").reachedAt()).isEqualTo(NOW);
        assertThat(known.getReachedAt()).isEqualTo(earlier);
        assertThat(byId.get("known").reachedAt()).isEqualTo(earlier);
    }

    @Test
    void list_newOpenTodoAfterReaching_keepsReachedAt_andShowsReopened() {
        Instant earlier = NOW.minus(Duration.ofDays(2));
        GoalMilestone m = milestone("m", NOW.minus(Duration.ofDays(1)), earlier);
        goalWith(m);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "m"), row("t2", Todo.Status.OPEN, "m")));

        GoalMilestoneResponse response = service.list("g1").get(0);

        assertThat(response.state()).as("wieder offen: der Zustand folgt dem Datum").isEqualTo(State.OVERDUE);
        assertThat(response.reopened()).isTrue();
        assertThat(response.reachedAt()).isEqualTo(earlier);
        assertThat(m.getReachedAt()).isEqualTo(earlier);
    }

    @Test
    void list_unknownGoal_is404() {
        when(goalRepository.findWithMilestones("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_trimsTitle_andReadsADateAsTheEndOfThatDay() {
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(milestoneRepository.save(any())).thenAnswer(inv -> {
            GoalMilestone m = inv.getArgument(0);
            m.setId("new");
            return m;
        });

        GoalMilestoneResponse created = service.create("g1", new CreateGoalMilestoneRequest("  Beta  ", "2026-10-01"));

        assertThat(created.id()).isEqualTo("new");
        assertThat(created.goalId()).isEqualTo("g1");
        assertThat(created.title()).isEqualTo("Beta");
        assertThat(created.dueDate()).isEqualTo(Instant.parse("2026-10-01T23:59:59Z"));
        assertThat(created.state()).isEqualTo(State.OPEN);
        assertThat(created.total()).isZero();
    }

    @Test
    void create_rejectsBlankOrOverlongTitle_badDate_andUnknownGoal() {
        when(goalRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest(" ", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest("x".repeat(201), "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", new CreateGoalMilestoneRequest("Beta", "morgen")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("nope", new CreateGoalMilestoneRequest("Beta", "2026-10-01")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(milestoneRepository, never()).save(any());
    }

    @Test
    void update_changesOnlyGivenFields_andRefusesAMilestoneOfAnotherGoal() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(9)), null);
        when(milestoneRepository.findById("m")).thenReturn(Optional.of(m));
        when(milestoneRepository.save(m)).thenReturn(m);
        when(todoRepository.countByMilestone(List.of("m"))).thenReturn(List.of());

        GoalMilestoneResponse updated = service.update("g1", "m",
                new UpdateGoalMilestoneRequest(null, "2026-11-02T12:00:00Z"));

        assertThat(updated.title()).isEqualTo("m");
        assertThat(updated.dueDate()).isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
        assertThatThrownBy(() -> service.update("other-goal", "m", new UpdateGoalMilestoneRequest("x", null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_detachesItsTodosFirst_thenDeletes() {
        GoalMilestone m = milestone("m", NOW, null);
        when(milestoneRepository.findById("m")).thenReturn(Optional.of(m));

        service.delete("g1", "m");

        var order = inOrder(todoRepository, milestoneRepository);
        order.verify(todoRepository).detachFromMilestone(eq("m"), any());
        order.verify(milestoneRepository).delete(m);
    }

    @Test
    void snapshot_carriesRowsMilestonesAndProgress() {
        GoalMilestone m = milestone("m", NOW.plus(Duration.ofDays(1)), null);
        goalWith(m);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "m"), row("t2", Todo.Status.OPEN, null)));

        GoalSnapshot snapshot = service.snapshot("g1");

        assertThat(snapshot.goal().getId()).isEqualTo("g1");
        assertThat(snapshot.rows()).hasSize(2);
        assertThat(snapshot.milestones()).singleElement().extracting(GoalMilestoneResponse::state).isEqualTo(State.REACHED);
        assertThat(snapshot.progress().done()).isEqualTo(1);
        assertThat(snapshot.progress().total()).isEqualTo(2);
    }

    private void goalWith(GoalMilestone... milestones) {
        Goal goal = Goal.builder().id("g1").title("Launch").build();
        when(goalRepository.findWithMilestones("g1"))
                .thenReturn(Optional.of(new GoalWithMilestones(goal, new ArrayList<>(List.of(milestones)))));
    }

    private static GoalMilestone milestone(String id, Instant due, Instant reachedAt) {
        return GoalMilestone.builder().id(id).goalId("g1").title(id).dueDate(due).reachedAt(reachedAt).build();
    }

    static GoalTodoRow row(String id, Todo.Status status, String milestoneId) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                null, NOW, NOW, null, milestoneId);
    }

    private static Map<String, GoalMilestoneResponse> byId(List<GoalMilestoneResponse> list) {
        return list.stream().collect(Collectors.toMap(GoalMilestoneResponse::id, Function.identity()));
    }
}
