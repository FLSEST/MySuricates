package com.automation.service.todo;

import com.automation.config.TodoHygieneConfig;
import com.automation.dto.projection.NeedsYouRow;
import com.automation.dto.projection.ProjectCount;
import com.automation.dto.projection.ProjectStatusCounts;
import com.automation.dto.response.TodoOverviewResponse;
import com.automation.entity.Project;
import com.automation.entity.Todo;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TodoOverviewServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private TodoHygieneConfig hygieneConfig;
    @Mock private com.automation.repository.GoalMilestoneRepository milestoneRepository;
    @Mock private com.automation.repository.GoalRepository goalRepository;

    @InjectMocks private TodoOverviewService service;

    @Test
    void getOverview_buildsRollups_filtersArchiveOnly_andEnrichesNeedsYou() {
        Instant now = Instant.now();
        when(hygieneConfig.getStaleDays()).thenReturn(30);

        when(todoRepository.aggregateByProject()).thenReturn(List.of(
                // p1: active work
                new ProjectStatusCounts("p1", 5, 1, 10, 2, 3, 1, 5),
                // p2: only DONE/ARCHIVED → must be filtered out of rollups
                new ProjectStatusCounts("p2", 0, 0, 4, 0, 7, 0, 0),
                // no-project bucket: active
                new ProjectStatusCounts(null, 2, 0, 0, 0, 0, 2, 0)
        ));
        when(todoRepository.staleCountByProject(any())).thenReturn(List.of(new ProjectCount("p1", 3)));
        when(todoRepository.findNeedsYou(any(), any())).thenReturn(List.of(
                new NeedsYouRow("t1", "User task", "p1", Todo.Priority.HIGH,
                        now.minus(1, ChronoUnit.DAYS), Todo.Source.USER,
                        now.minus(40, ChronoUnit.DAYS), now),
                new NeedsYouRow("t2", "Critical", "p1", Todo.Priority.CRITICAL,
                        null, Todo.Source.AI, now.minus(5, ChronoUnit.DAYS), now),
                new NeedsYouRow("t3", "No-project crit", null, Todo.Priority.CRITICAL,
                        null, Todo.Source.AI, now.minus(2, ChronoUnit.DAYS), now)
        ));
        when(projectRepository.findAllById(any())).thenReturn(List.of(
                Project.builder().id("p1").name("AutoPilot").build(),
                Project.builder().id("p2").name("Other").build()
        ));

        TodoOverviewResponse result = service.getOverview();

        assertThat(result.staleThresholdDays()).isEqualTo(30);

        // archive-only p2 filtered out → p1 + no-project bucket
        assertThat(result.projects()).hasSize(2);

        var p1 = result.projects().stream().filter(r -> "p1".equals(r.projectId())).findFirst().orElseThrow();
        assertThat(p1.projectName()).isEqualTo("AutoPilot");
        assertThat(p1.open()).isEqualTo(5);
        assertThat(p1.done()).isEqualTo(10);
        assertThat(p1.archived()).isEqualTo(3);
        assertThat(p1.staleCount()).isEqualTo(3);
        assertThat(p1.userActive()).isEqualTo(1);
        assertThat(p1.machineActive()).isEqualTo(5);
        assertThat(p1.needsYouCount()).isEqualTo(2); // t1 + t2

        var noProject = result.projects().stream().filter(r -> r.projectId() == null).findFirst().orElseThrow();
        assertThat(noProject.projectName()).isNull();
        assertThat(noProject.open()).isEqualTo(2);
        assertThat(noProject.needsYouCount()).isEqualTo(1); // t3

        // projects sorted by needsYouCount desc → p1 (2) before no-project (1)
        assertThat(result.projects().get(0).projectId()).isEqualTo("p1");

        // needs-you enrichment
        assertThat(result.needsYou()).hasSize(3);
        var t1 = result.needsYou().get(0);
        assertThat(t1.id()).isEqualTo("t1");
        assertThat(t1.projectName()).isEqualTo("AutoPilot");
        assertThat(t1.ageDays()).isGreaterThanOrEqualTo(39);
        assertThat(t1.overdue()).isTrue();
        var t2 = result.needsYou().get(1);
        assertThat(t2.overdue()).isFalse(); // no due date
    }

    @Test
    void getOverview_handlesEmptyData() {
        when(hygieneConfig.getStaleDays()).thenReturn(30);
        when(todoRepository.aggregateByProject()).thenReturn(List.of());
        when(todoRepository.staleCountByProject(any())).thenReturn(List.of());
        when(todoRepository.findNeedsYou(any(), any())).thenReturn(List.of());

        TodoOverviewResponse result = service.getOverview();

        assertThat(result.projects()).isEmpty();
        assertThat(result.needsYou()).isEmpty();
        assertThat(result.staleThresholdDays()).isEqualTo(30);
    }

    // --- Meilensteine in „Braucht dich“ (TODO 259f4f66) ---------------------------------

    @Test
    void getOverview_listsDueSoonAndOverdueMilestones_butNotReachedOnes_withTheirGoal() {
        Instant now = Instant.now();
        when(hygieneConfig.getStaleDays()).thenReturn(30);
        when(todoRepository.aggregateByProject()).thenReturn(List.of());
        when(todoRepository.staleCountByProject(any())).thenReturn(List.of());
        when(todoRepository.findNeedsYou(any(), any())).thenReturn(List.of());
        var overdue = com.automation.entity.GoalMilestone.builder().id("m1").goalId("g1").title("Alpha")
                .dueDate(now.minus(2, ChronoUnit.DAYS)).build();
        var soon = com.automation.entity.GoalMilestone.builder().id("m2").goalId("g1").title("Beta")
                .dueDate(now.plus(2, ChronoUnit.DAYS)).build();
        var reached = com.automation.entity.GoalMilestone.builder().id("m3").goalId("g1").title("Done")
                .dueDate(now.minus(1, ChronoUnit.DAYS)).build();
        when(milestoneRepository.findDueForActiveGoals(any(), any())).thenReturn(List.of(overdue, soon, reached));
        when(todoRepository.countByMilestone(any())).thenReturn(List.of(
                new com.automation.dto.projection.MilestoneTodoCounts("m1", 3, 1),
                new com.automation.dto.projection.MilestoneTodoCounts("m3", 2, 2)));
        when(goalRepository.findAllById(any())).thenReturn(List.of(
                com.automation.entity.Goal.builder().id("g1").title("Launch").build()));

        TodoOverviewResponse result = service.getOverview();

        assertThat(result.milestonesDue()).extracting(m -> m.id()).containsExactly("m1", "m2");
        var first = result.milestonesDue().get(0);
        assertThat(first.state()).isEqualTo(com.automation.entity.GoalMilestone.State.OVERDUE);
        assertThat(first.goalId()).isEqualTo("g1");
        assertThat(first.goalTitle()).isEqualTo("Launch");
        assertThat(first.total()).isEqualTo(3);
        assertThat(first.done()).isEqualTo(1);
        assertThat(result.milestonesDue().get(1).state()).isEqualTo(com.automation.entity.GoalMilestone.State.DUE_SOON);
    }

    /** TODO c60bf914: ein Teilziel ohne Datum ist nie faellig, selbst wenn es als Kandidat auftaucht. */
    @Test
    void getOverview_neverListsAnUndatedMilestone() {
        when(hygieneConfig.getStaleDays()).thenReturn(30);
        when(todoRepository.aggregateByProject()).thenReturn(List.of());
        when(todoRepository.staleCountByProject(any())).thenReturn(List.of());
        when(todoRepository.findNeedsYou(any(), any())).thenReturn(List.of());
        var undated = com.automation.entity.GoalMilestone.builder().id("m1").goalId("g1").title("Irgendwann").build();
        when(milestoneRepository.findDueForActiveGoals(any(), any())).thenReturn(List.of(undated));
        when(todoRepository.countByMilestone(any())).thenReturn(List.of(
                new com.automation.dto.projection.MilestoneTodoCounts("m1", 3, 1)));
        when(goalRepository.findAllById(any())).thenReturn(List.of(
                com.automation.entity.Goal.builder().id("g1").title("Launch").build()));

        assertThat(service.getOverview().milestonesDue()).isEmpty();
    }

    @Test
    void getOverview_withoutDueMilestones_asksNothingMore() {
        when(hygieneConfig.getStaleDays()).thenReturn(30);
        when(todoRepository.aggregateByProject()).thenReturn(List.of());
        when(todoRepository.staleCountByProject(any())).thenReturn(List.of());
        when(todoRepository.findNeedsYou(any(), any())).thenReturn(List.of());
        when(milestoneRepository.findDueForActiveGoals(any(), any())).thenReturn(List.of());

        TodoOverviewResponse result = service.getOverview();

        assertThat(result.milestonesDue()).isEmpty();
        org.mockito.Mockito.verify(todoRepository, org.mockito.Mockito.never()).countByMilestone(any());
        org.mockito.Mockito.verifyNoInteractions(goalRepository);
    }
}
