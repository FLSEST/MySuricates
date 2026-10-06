package com.automation.service.goal;

import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.projection.GoalWithWorkPackages;
import com.automation.dto.request.CreateGoalWorkPackageRequest;
import com.automation.dto.request.UpdateGoalWorkPackageRequest;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.ProjectRepository;
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

/**
 * Work packages: states, the completedAt rule, period and maintenance (TODO 259f4f66, c60bf914 goals
 * stage 1: goal → projects → work packages → TODOs, milestones, outline and timeline).
 */
@ExtendWith(MockitoExtension.class)
class GoalWorkPackageServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-24T10:00:00Z");

    @Mock private GoalRepository goalRepository;
    @Mock private GoalWorkPackageRepository workPackageRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private GoalProjectRepository goalProjectRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private GoalMilestoneWorkPackageRepository linkRepository;

    private GoalWorkPackageService service;

    @BeforeEach
    void setUp() {
        service = new GoalWorkPackageService(goalRepository, workPackageRepository, todoRepository,
                goalProjectRepository, projectRepository, linkRepository, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void list_computesEachState_fromTheEndAndAssignedTodos() {
        GoalWorkPackage open = workPackage("open", NOW.plus(Duration.ofDays(10)), null);
        GoalWorkPackage soon = workPackage("soon", NOW.plus(Duration.ofDays(3)), null);
        GoalWorkPackage overdue = workPackage("overdue", NOW.minus(Duration.ofHours(1)), null);
        GoalWorkPackage reached = workPackage("reached", NOW.minus(Duration.ofDays(5)), null);
        goalWith(open, soon, overdue, reached);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "open"), row("t2", Todo.Status.OPEN, "open"),
                row("t3", Todo.Status.IN_PROGRESS, "overdue"),
                row("t4", Todo.Status.DONE, "reached"), row("t5", Todo.Status.DONE, "reached"),
                row("t6", Todo.Status.OPEN, null)));

        Map<String, GoalWorkPackageResponse> byId = byId(service.list("g1"));

        assertThat(byId.get("open").state()).isEqualTo(GoalDueState.OPEN);
        assertThat(byId.get("open").total()).isEqualTo(2);
        assertThat(byId.get("open").done()).isEqualTo(1);
        assertThat(byId.get("soon").state()).as("genau drei Tage vorher: bald faellig").isEqualTo(GoalDueState.DUE_SOON);
        assertThat(byId.get("soon").total()).isZero();
        assertThat(byId.get("overdue").state()).isEqualTo(GoalDueState.OVERDUE);
        assertThat(byId.get("reached").state()).as("abgeschlossen schlaegt ueberfaellig").isEqualTo(GoalDueState.REACHED);
        assertThat(byId.get("reached").reopened()).isFalse();
    }

    @Test
    void list_emptyPackageIsNeverReached_andArchivedTodosDoNotCount() {
        GoalWorkPackage empty = workPackage("empty", NOW.plus(Duration.ofDays(30)), null);
        GoalWorkPackage onlyArchived = workPackage("archived", NOW.plus(Duration.ofDays(30)), null);
        GoalWorkPackage doneAndArchived = workPackage("mixed", NOW.plus(Duration.ofDays(30)), null);
        goalWith(empty, onlyArchived, doneAndArchived);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.ARCHIVED, "archived"),
                row("t2", Todo.Status.DONE, "mixed"), row("t3", Todo.Status.ARCHIVED, "mixed")));

        Map<String, GoalWorkPackageResponse> byId = byId(service.list("g1"));

        assertThat(byId.get("empty").state()).isEqualTo(GoalDueState.OPEN);
        assertThat(byId.get("archived").state()).isEqualTo(GoalDueState.OPEN);
        assertThat(byId.get("archived").total()).isZero();
        assertThat(byId.get("mixed").state()).isEqualTo(GoalDueState.REACHED);
        assertThat(byId.get("mixed").total()).isEqualTo(1);
    }

    @Test
    void list_firstDetectionSetsCompletedAt_andKeepsAnEarlierOne() {
        Instant earlier = NOW.minus(Duration.ofDays(2));
        GoalWorkPackage fresh = workPackage("fresh", NOW.plus(Duration.ofDays(9)), null);
        GoalWorkPackage known = workPackage("known", NOW.plus(Duration.ofDays(9)), earlier);
        goalWith(fresh, known);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "fresh"), row("t2", Todo.Status.DONE, "known")));

        Map<String, GoalWorkPackageResponse> byId = byId(service.list("g1"));

        assertThat(fresh.getCompletedAt()).as("an der Entity gesetzt, die Transaktion speichert").isEqualTo(NOW);
        assertThat(byId.get("fresh").completedAt()).isEqualTo(NOW);
        assertThat(known.getCompletedAt()).isEqualTo(earlier);
        assertThat(byId.get("known").completedAt()).isEqualTo(earlier);
    }

    @Test
    void list_newOpenTodoAfterCompletion_keepsCompletedAt_andShowsReopened() {
        Instant earlier = NOW.minus(Duration.ofDays(2));
        GoalWorkPackage p = workPackage("p", NOW.minus(Duration.ofDays(1)), earlier);
        goalWith(p);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", Todo.Status.DONE, "p"), row("t2", Todo.Status.OPEN, "p")));

        GoalWorkPackageResponse response = service.list("g1").get(0);

        assertThat(response.state()).as("wieder offen: der Zustand folgt dem Ende").isEqualTo(GoalDueState.OVERDUE);
        assertThat(response.reopened()).isTrue();
        assertThat(response.completedAt()).isEqualTo(earlier);
        assertThat(p.getCompletedAt()).isEqualTo(earlier);
    }

    @Test
    void list_unknownGoal_is404() {
        when(goalRepository.findWithWorkPackages("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_trimsTitle_readsStartAsBeginAndEndAsEndOfDay_andTakesTheOnlyGoalProject() {
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(goalProjectRepository.findRows("g1")).thenReturn(List.of(project("pA", 0)));
        when(workPackageRepository.maxPosition("g1", "pA")).thenReturn(null);
        when(workPackageRepository.save(any())).thenAnswer(inv -> {
            GoalWorkPackage p = inv.getArgument(0);
            p.setId("new");
            return p;
        });

        GoalWorkPackageResponse created = service.create("g1",
                new CreateGoalWorkPackageRequest("  Beta  ", null, "2026-10-01", "2026-10-03"));

        assertThat(created.id()).isEqualTo("new");
        assertThat(created.goalId()).isEqualTo("g1");
        assertThat(created.title()).isEqualTo("Beta");
        assertThat(created.startDate()).isEqualTo(Instant.parse("2026-10-01T00:00:00Z"));
        assertThat(created.endDate()).isEqualTo(Instant.parse("2026-10-03T23:59:59Z"));
        assertThat(created.state()).isEqualTo(GoalDueState.OPEN);
        assertThat(created.total()).isZero();
        assertThat(created.projectId()).as("das einzige Projekt des Ziels").isEqualTo("pA");
        assertThat(created.position()).isZero();
    }

    @Test
    void create_rejectsBlankOrOverlongTitle_badDate_andUnknownGoal() {
        when(goalRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.create("g1", request(" ", "2026-10-01", "2026-10-02")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", request("x".repeat(201), "2026-10-01", "2026-10-02")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("g1", request("Beta", "morgen", "2026-10-02")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.create("nope", request("Beta", "2026-10-01", "2026-10-02")))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(workPackageRepository, never()).save(any());
    }

    /** A period has start and end or neither, and the start is never after the end. */
    @Test
    void create_periodNeedsBothDates_andStartNotAfterEnd_sameDayIsFine() {
        assertThatThrownBy(() -> service.create("g1", request("Beta", "2026-10-01", null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Start und Ende");
        assertThatThrownBy(() -> service.create("g1", request("Beta", null, "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Start und Ende");
        assertThatThrownBy(() -> service.create("g1", request("Beta", "2026-10-05", "2026-10-01")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("nach dem Ende");
        assertThatThrownBy(() -> service.create("g1", request("Beta", "2026-10-05T10:00:00Z", "2026-10-05T09:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workPackageRepository, never()).save(any());

        GoalWorkPackageService.requireValidPeriod(Instant.parse("2026-10-05T00:00:00Z"), Instant.parse("2026-10-05T23:59:59Z"));
        GoalWorkPackageService.requireValidPeriod(Instant.parse("2026-10-05T12:00:00Z"), Instant.parse("2026-10-05T12:00:00Z"));
        GoalWorkPackageService.requireValidPeriod(null, null);
    }

    /** Without a project only if the goal has exactly one. */
    @Test
    void create_withoutProject_needsExactlyOneGoalProject() {
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(goalProjectRepository.findRows("g1")).thenReturn(List.of(), List.of(project("pA", 0), project("pB", 1)));

        assertThatThrownBy(() -> service.create("g1", request("Beta", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("0 Projekte");
        assertThatThrownBy(() -> service.create("g1", request("Beta", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 Projekte");
        verify(workPackageRepository, never()).save(any());
    }

    @Test
    void create_withProject_withoutPeriod_linksTheProject_andAppendsToItsPackages() {
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(projectRepository.existsById("pB")).thenReturn(true);
        when(workPackageRepository.maxPosition("g1", "pB")).thenReturn(2);
        when(workPackageRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GoalWorkPackageResponse created = service.create("g1", new CreateGoalWorkPackageRequest("Werbematerial", "pB", "", ""));

        verify(goalProjectRepository).linkIfAbsent("g1", "pB");
        assertThat(created.startDate()).isNull();
        assertThat(created.endDate()).isNull();
        assertThat(created.state()).isEqualTo(GoalDueState.OPEN);
        assertThat(created.projectId()).isEqualTo("pB");
        assertThat(created.position()).isEqualTo(3);
    }

    @Test
    void create_withUnknownProject_is404() {
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(projectRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.create("g1", new CreateGoalWorkPackageRequest("Beta", "nope", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
    }

    @Test
    void update_changesOnlyGivenFields_andRefusesAPackageOfAnotherGoal() {
        GoalWorkPackage p = workPackage("p", NOW.plus(Duration.ofDays(9)), null);
        when(workPackageRepository.findById("p")).thenReturn(Optional.of(p));
        when(workPackageRepository.save(p)).thenReturn(p);
        when(todoRepository.countByWorkPackage(List.of("p"))).thenReturn(List.of());

        GoalWorkPackageResponse updated = service.update("g1", "p",
                new UpdateGoalWorkPackageRequest(null, null, null, "2026-11-02T12:00:00Z"));

        assertThat(updated.title()).isEqualTo("p");
        assertThat(updated.startDate()).as("Start bleibt").isEqualTo(NOW.plus(Duration.ofDays(9)));
        assertThat(updated.endDate()).isEqualTo(Instant.parse("2026-11-02T12:00:00Z"));
        verify(todoRepository, never()).countByWorkPackageId(any());
        assertThatThrownBy(() -> service.update("other-goal", "p", new UpdateGoalWorkPackageRequest("x", null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void update_emptyStringsClearThePeriod_halfAPeriodOrAReversedOneIs400() {
        GoalWorkPackage p = workPackage("p", NOW.plus(Duration.ofDays(9)), null);
        when(workPackageRepository.findById("p")).thenReturn(Optional.of(p));

        assertThatThrownBy(() -> service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, null, "", null)))
                .as("nur den Start leeren liesse ein Ende ohne Start").isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, null, "2026-12-24", null)))
                .as("Start nach dem bestehenden Ende").isInstanceOf(IllegalArgumentException.class);
        assertThat(p.getStartDate()).as("nichts geaendert").isEqualTo(NOW.plus(Duration.ofDays(9)));
        verify(workPackageRepository, never()).save(any());

        when(workPackageRepository.save(p)).thenReturn(p);
        when(todoRepository.countByWorkPackage(List.of("p"))).thenReturn(List.of());
        GoalWorkPackageResponse cleared = service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, null, "", ""));

        assertThat(cleared.startDate()).isNull();
        assertThat(cleared.endDate()).isNull();
        assertThat(cleared.state()).isEqualTo(GoalDueState.OPEN);
        assertThatThrownBy(() -> service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, null, null, "2026-12-24")))
                .as("ein Ende ohne Start").isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void update_projectChangeMovesAnEmptyPackageToTheEnd() {
        GoalWorkPackage p = workPackage("p", NOW.plus(Duration.ofDays(9)), null);
        p.setProjectId("pA");
        p.setPosition(0);
        when(workPackageRepository.findById("p")).thenReturn(Optional.of(p));
        when(workPackageRepository.save(p)).thenReturn(p);
        when(todoRepository.countByWorkPackage(List.of("p"))).thenReturn(List.of());
        when(todoRepository.countByWorkPackageId("p")).thenReturn(0L);
        when(projectRepository.existsById("pB")).thenReturn(true);
        when(workPackageRepository.maxPosition("g1", "pB")).thenReturn(null);

        GoalWorkPackageResponse updated = service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, "pB", null, null));

        assertThat(updated.projectId()).isEqualTo("pB");
        assertThat(updated.position()).isZero();
        verify(goalProjectRepository).linkIfAbsent("g1", "pB");
    }

    @Test
    void update_projectChangeOfAPackageWithTodos_is400() {
        GoalWorkPackage p = workPackage("p", null, null);
        p.setProjectId("pA");
        when(workPackageRepository.findById("p")).thenReturn(Optional.of(p));
        when(todoRepository.countByWorkPackageId("p")).thenReturn(2L);

        assertThatThrownBy(() -> service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, "pB", null, null)))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("2 TODO");
        assertThatThrownBy(() -> service.update("g1", "p", new UpdateGoalWorkPackageRequest(null, " ", null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workPackageRepository, never()).save(any());
        assertThat(p.getProjectId()).isEqualTo("pA");
    }

    @Test
    void reorder_setsPositionsWithinOneProject_appendsTheUnnamed_andRefusesMixedProjects() {
        GoalWorkPackage a0 = inProject("a0", "pA", 0);
        GoalWorkPackage a1 = inProject("a1", "pA", 1);
        GoalWorkPackage a2 = inProject("a2", "pA", 2);
        GoalWorkPackage b0 = inProject("b0", "pB", 0);
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(workPackageRepository.findByGoalId("g1")).thenReturn(List.of(a0, a1, a2, b0));
        goalWith(a0, a1, a2, b0);
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of());

        service.reorder("g1", List.of("a2", "a0"));

        assertThat(a2.getPosition()).isZero();
        assertThat(a0.getPosition()).isEqualTo(1);
        assertThat(a1.getPosition()).as("nicht genannt: dahinter").isEqualTo(2);
        assertThat(b0.getPosition()).as("anderes Projekt bleibt").isZero();

        assertThatThrownBy(() -> service.reorder("g1", List.of("a0", "b0")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("innerhalb eines Projekts");
        assertThatThrownBy(() -> service.reorder("g1", List.of("fremd")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_unlinksMilestonesAndDetachesItsTodosFirst_thenDeletes() {
        GoalWorkPackage p = workPackage("p", NOW, null);
        when(workPackageRepository.findById("p")).thenReturn(Optional.of(p));

        service.delete("g1", "p");

        var order = inOrder(linkRepository, todoRepository, workPackageRepository);
        order.verify(linkRepository).unlinkWorkPackage("p");
        order.verify(todoRepository).detachFromWorkPackage(eq("p"), any());
        order.verify(workPackageRepository).deleteById("p");
    }

    private void goalWith(GoalWorkPackage... workPackages) {
        Goal goal = Goal.builder().id("g1").title("Launch").build();
        when(goalRepository.findWithWorkPackages("g1"))
                .thenReturn(Optional.of(new GoalWithWorkPackages(goal, new ArrayList<>(List.of(workPackages)))));
    }

    /** A package with end {@code end} (start = end) or without a period. */
    private static GoalWorkPackage workPackage(String id, Instant end, Instant completedAt) {
        return GoalWorkPackage.builder().id(id).goalId("g1").title(id).startDate(end).endDate(end)
                .completedAt(completedAt).build();
    }

    private static GoalWorkPackage inProject(String id, String projectId, int position) {
        return GoalWorkPackage.builder().id(id).goalId("g1").title(id).projectId(projectId).position(position).build();
    }

    private static CreateGoalWorkPackageRequest request(String title, String start, String end) {
        return new CreateGoalWorkPackageRequest(title, null, start, end);
    }

    private static GoalProjectRow project(String projectId, int position) {
        return new GoalProjectRow("g1", projectId, projectId, null, position);
    }

    static GoalTodoRow row(String id, Todo.Status status, String workPackageId) {
        return new GoalTodoRow(id, id, status, Todo.Priority.MEDIUM, null, null, null, null, null, null, null, null,
                null, NOW, NOW, null, workPackageId);
    }

    private static Map<String, GoalWorkPackageResponse> byId(List<GoalWorkPackageResponse> list) {
        return list.stream().collect(Collectors.toMap(GoalWorkPackageResponse::id, Function.identity()));
    }
}
