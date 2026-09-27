package com.automation.service.todo;

import com.automation.entity.GoalMilestone;
import com.automation.entity.Todo;
import com.automation.repository.GoalMilestoneRepository;
import com.automation.repository.GoalProjectRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Projektwechsel eines TODOs im Ziel (TODO c60bf914 Ziele Stufe 1: Ziel → Projekte → Teilziele →
 * TODOs): das neue Projekt kommt ans Ziel, ein Teilziel eines anderen Projekts faellt weg.
 */
@ExtendWith(MockitoExtension.class)
class TodoGoalConsistencyTest {

    @Mock private GoalProjectRepository goalProjectRepository;
    @Mock private GoalMilestoneRepository milestoneRepository;

    private TodoGoalConsistency consistency;

    @BeforeEach
    void setUp() {
        consistency = new TodoGoalConsistency(goalProjectRepository, milestoneRepository);
    }

    @Test
    void newProject_isLinkedToTheGoal_andAMilestoneOfTheOldProjectIsCleared() {
        Todo todo = Todo.builder().id("t").goalId("g1").projectId("pB").milestoneId("mA").build();
        when(milestoneRepository.findById("mA"))
                .thenReturn(Optional.of(GoalMilestone.builder().id("mA").goalId("g1").projectId("pA").build()));

        consistency.afterProjectChange(todo);

        verify(goalProjectRepository).linkIfAbsent("g1", "pB");
        assertThat(todo.getMilestoneId()).isNull();
    }

    @Test
    void milestoneOfTheNewProject_orWithoutProject_stays() {
        Todo fits = Todo.builder().id("t1").goalId("g1").projectId("pB").milestoneId("mB").build();
        Todo legacy = Todo.builder().id("t2").goalId("g1").projectId("pB").milestoneId("mLegacy").build();
        when(milestoneRepository.findById("mB"))
                .thenReturn(Optional.of(GoalMilestone.builder().id("mB").goalId("g1").projectId("pB").build()));
        when(milestoneRepository.findById("mLegacy"))
                .thenReturn(Optional.of(GoalMilestone.builder().id("mLegacy").goalId("g1").build()));

        consistency.afterProjectChange(fits);
        consistency.afterProjectChange(legacy);

        assertThat(fits.getMilestoneId()).isEqualTo("mB");
        assertThat(legacy.getMilestoneId()).isEqualTo("mLegacy");
    }

    @Test
    void projectRemoved_linksNothing_butLeavesAProjectMilestone() {
        Todo todo = Todo.builder().id("t").goalId("g1").projectId(null).milestoneId("mA").build();
        when(milestoneRepository.findById("mA"))
                .thenReturn(Optional.of(GoalMilestone.builder().id("mA").goalId("g1").projectId("pA").build()));

        consistency.afterProjectChange(todo);

        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
        assertThat(todo.getMilestoneId()).as("ohne Projekt passt es in kein Teilziel mit Projekt").isNull();
    }

    @Test
    void todoWithoutGoal_isLeftAlone() {
        Todo todo = Todo.builder().id("t").projectId("pB").build();

        consistency.afterProjectChange(todo);

        verifyNoInteractions(goalProjectRepository, milestoneRepository);
    }
}
