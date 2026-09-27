package com.automation.service.goal;

import com.automation.entity.Goal;
import com.automation.entity.GoalProject;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Projekte eines Ziels zuordnen, entfernen und ordnen (TODO c60bf914 Ziele Stufe 1: Ziel → Projekte →
 * Teilziele → TODOs, Gliederungsansicht und Zeitstrahl mit Projektbaendern).
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalProjectServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-27T10:00:00Z");

    @Mock private GoalService goalService;
    @Mock private GoalProjectRepository goalProjectRepository;
    @Mock private GoalMilestoneRepository milestoneRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;

    private GoalProjectService service;
    private Goal goal;

    @BeforeEach
    void setUp() {
        service = new GoalProjectService(goalService, goalProjectRepository, milestoneRepository, todoRepository,
                projectRepository, Clock.fixed(NOW, ZoneOffset.UTC));
        goal = Goal.builder().id("g1").title("Release").build();
        when(goalService.find("g1")).thenReturn(goal);
    }

    @Test
    void add_linksAnExistingProject_idempotentlyViaTheRepository() {
        when(projectRepository.existsById("pA")).thenReturn(true);

        service.add("g1", " pA ");

        verify(goalProjectRepository).linkIfAbsent("g1", "pA");
        verify(goalService).detailOf(goal);
    }

    @Test
    void add_unknownProject_is404() {
        when(projectRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.add("g1", "nope")).isInstanceOf(ResourceNotFoundException.class);
        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
    }

    @Test
    void remove_withMilestonesOrTodos_isAConflictNamingTheCounts() {
        when(milestoneRepository.countByGoalIdAndProjectId("g1", "pA")).thenReturn(2L);
        when(todoRepository.countByGoalIdAndProjectId("g1", "pA")).thenReturn(5L);

        assertThatThrownBy(() -> service.remove("g1", "pA", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("2 Teilziel").hasMessageContaining("5 TODO");
        verify(goalProjectRepository, never()).unlink(anyString(), anyString());
    }

    @Test
    void remove_emptyProject_justUnlinks() {
        service.remove("g1", "pA", false);

        verify(goalProjectRepository).unlink("g1", "pA");
        verify(milestoneRepository, never()).deleteByGoalAndProject(any(), any());
        verify(todoRepository, never()).detachProjectFromGoal(any(), any(), any());
    }

    @Test
    void remove_cascade_detachesMilestoneTodos_deletesTheMilestones_releasesTheTodos_thenUnlinks() {
        when(milestoneRepository.countByGoalIdAndProjectId("g1", "pA")).thenReturn(2L);
        when(todoRepository.countByGoalIdAndProjectId("g1", "pA")).thenReturn(3L);
        when(milestoneRepository.findIdsByGoalAndProject("g1", "pA")).thenReturn(List.of("m1", "m2"));

        service.remove("g1", "pA", true);

        InOrder order = inOrder(todoRepository, milestoneRepository, goalProjectRepository);
        order.verify(todoRepository).detachFromMilestone("m1", NOW);
        order.verify(todoRepository).detachFromMilestone("m2", NOW);
        order.verify(milestoneRepository).deleteByGoalAndProject("g1", "pA");
        order.verify(todoRepository).detachProjectFromGoal("g1", "pA", NOW);
        order.verify(goalProjectRepository).unlink("g1", "pA");
    }

    @Test
    void reorder_namedFirst_restInTheirOldOrder_foreignProjectIs400() {
        GoalProject a = new GoalProject("g1", "pA", 0);
        GoalProject b = new GoalProject("g1", "pB", 1);
        GoalProject c = new GoalProject("g1", "pC", 2);
        when(goalProjectRepository.findByGoalIdOrderByPositionAscProjectIdAsc("g1")).thenReturn(List.of(a, b, c));

        service.reorder("g1", List.of("pC", "pA"));

        assertThat(c.getPosition()).isZero();
        assertThat(a.getPosition()).isEqualTo(1);
        assertThat(b.getPosition()).isEqualTo(2);
        verify(goalProjectRepository).saveAll(List.of(c, a, b));
        verify(goalService).detailOf(goal);

        assertThatThrownBy(() -> service.reorder("g1", List.of("pX")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pX");
    }
}
