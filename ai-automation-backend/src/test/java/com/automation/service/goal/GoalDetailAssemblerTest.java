package com.automation.service.goal;

import com.automation.dto.projection.GoalMilestoneLinks;
import com.automation.dto.projection.GoalProjectRow;
import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalDetailResponse.ProjectProgress;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalTodoItem;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalDueState;
import com.automation.entity.GoalMilestone;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GoalDetailAssemblerTest {

    private static final Instant NOW = Instant.parse("2026-10-07T10:00:00Z");
    private static final Instant CREATED = Instant.parse("2026-09-01T00:00:00Z");

    private final TodoRepository todos = mock(TodoRepository.class);
    private final ProjectRepository projects = mock(ProjectRepository.class);
    private final GoalProjectRepository goalProjects = mock(GoalProjectRepository.class);
    private final GoalMilestoneRepository milestones = mock(GoalMilestoneRepository.class);
    private final GoalGuardLookup guardLookup = mock(GoalGuardLookup.class);
    private final GoalDetailAssembler assembler = new GoalDetailAssembler(todos, projects, goalProjects, milestones,
            guardLookup, Clock.fixed(NOW, ZoneOffset.UTC));

    private final Goal goal = Goal.builder().id("g-1").title("Release").build();
    private final GoalGuardResponse guard = new GoalGuardResponse(true, "wf-guard", "s-1", NOW);

    private static GoalTodoRow row(String id, Integer rank, String projectId, Todo.Status status, String workPackageId) {
        return new GoalTodoRow(id, "Title " + id, status, Todo.Priority.MEDIUM, projectId, "[\"ux\",\"bug\"]", rank,
                null, null, null, null, null, null, CREATED, CREATED, null, workPackageId);
    }

    @BeforeEach
    void setUp() {
        when(guardLookup.of(goal)).thenReturn(guard);
        when(goalProjects.findRows("g-1")).thenReturn(List.of(new GoalProjectRow("g-1", "pA", "Alpha", null, 0)));
    }

    @Test
    void listsTodosInWorkOrderWithProjectNames_loadingOnlyTheUnassignedNames() {
        when(todos.findGoalRows("g-1")).thenReturn(List.of(
                row("unranked", null, null, Todo.Status.OPEN, null),
                row("second", 2, "pLegacy", Todo.Status.DONE, null),
                row("first", 1, "pA", Todo.Status.OPEN, null)));
        when(projects.findIdAndNameByIdIn(Set.of("pLegacy")))
                .thenReturn(List.<Object[]>of(new Object[]{"pLegacy", "Legacy"}));

        GoalDetailResponse detail = assembler.detailOf(goal, List.of());

        assertThat(detail.todos()).extracting(GoalTodoItem::id).containsExactly("first", "second", "unranked");
        assertThat(detail.todos()).extracting(GoalTodoItem::projectName).containsExactly("Alpha", "Legacy", null);
        assertThat(detail.todos().get(0).tags()).containsExactly("ux", "bug");
        assertThat(detail.byProject()).containsExactlyInAnyOrder(
                new ProjectProgress("pA", "Alpha", 1, 0),
                new ProjectProgress("pLegacy", "Legacy", 1, 1),
                new ProjectProgress(null, null, 1, 0));
        assertThat(detail.projects()).singleElement().satisfies(p -> {
            assertThat(p.projectId()).isEqualTo("pA");
            assertThat(p.total()).isEqualTo(1);
        });
        assertThat(detail.goal().id()).isEqualTo("g-1");
        assertThat(detail.goal().guard()).isSameAs(guard);
        assertThat(detail.goal().progress().total()).isEqualTo(3);
        assertThat(detail.goal().progress().done()).isEqualTo(1);
        assertThat(detail.goal().projects()).extracting(p -> p.name()).containsExactly("Alpha");
        assertThat(detail.workPackages()).isEmpty();
        assertThat(detail.milestones()).isEmpty();
        assertThat(detail.goal().nextMilestone()).isNull();
    }

    @Test
    void noExtraNameQueryWhenEveryProjectIsAssigned() {
        when(todos.findGoalRows("g-1")).thenReturn(List.of(row("a", null, "pA", Todo.Status.OPEN, null),
                row("n", null, null, Todo.Status.OPEN, null)));

        assembler.detailOf(goal, List.of());

        verify(projects, never()).findIdAndNameByIdIn(any());
    }

    @Test
    void marksAPackageAndItsMilestoneAsReachedOnFirstRecognition() {
        GoalWorkPackage done = GoalWorkPackage.builder().id("wp-done").goalId("g-1").projectId("pA").title("Done")
                .createdAt(CREATED).build();
        GoalWorkPackage open = GoalWorkPackage.builder().id("wp-open").goalId("g-1").title("Open").createdAt(CREATED)
                .build();
        GoalMilestone reached = GoalMilestone.builder().id("m-reached").goalId("g-1").title("Beta").build();
        GoalMilestone pending = GoalMilestone.builder().id("m-pending").goalId("g-1").title("Final")
                .dueDate(NOW.plusSeconds(86_400)).build();
        when(todos.findGoalRows("g-1")).thenReturn(List.of(
                row("t1", null, "pA", Todo.Status.DONE, "wp-done"),
                row("t2", null, null, Todo.Status.OPEN, "wp-open")));
        when(milestones.findWithLinks("g-1")).thenReturn(List.of(
                new GoalMilestoneLinks(reached, List.of("wp-done")),
                new GoalMilestoneLinks(pending, List.of("wp-done", "wp-open"))));

        GoalDetailResponse detail = assembler.detailOf(goal, List.of(open, done));

        assertThat(detail.workPackages()).extracting(GoalWorkPackageResponse::id)
                .as("packages of goal projects first").containsExactly("wp-done", "wp-open");
        assertThat(detail.workPackages().get(0).state()).isEqualTo(GoalDueState.REACHED);
        assertThat(done.getCompletedAt()).isEqualTo(NOW);
        assertThat(open.getCompletedAt()).isNull();
        assertThat(reached.getReachedAt()).isEqualTo(NOW);
        assertThat(pending.getReachedAt()).isNull();
        assertThat(detail.goal().nextMilestone()).isNotNull();
        assertThat(detail.goal().nextMilestone().id()).isEqualTo("m-pending");
    }
}
