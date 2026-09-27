package com.automation.service.todo;

import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import com.automation.repository.GoalWorkPackageRepository;
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
 * Projektwechsel eines TODOs im Ziel (TODO c60bf914 Ziele Stufe 1: Ziel → Projekte → Arbeitspakete →
 * TODOs, Meilensteine, Gliederung und Zeitstrahl): das neue Projekt kommt ans Ziel, ein Arbeitspaket eines
 * anderen Projekts faellt weg.
 */
@ExtendWith(MockitoExtension.class)
class TodoGoalConsistencyTest {

    @Mock private GoalProjectRepository goalProjectRepository;
    @Mock private GoalWorkPackageRepository workPackageRepository;

    private TodoGoalConsistency consistency;

    @BeforeEach
    void setUp() {
        consistency = new TodoGoalConsistency(goalProjectRepository, workPackageRepository);
    }

    @Test
    void newProject_isLinkedToTheGoal_andAWorkPackageOfTheOldProjectIsCleared() {
        Todo todo = Todo.builder().id("t").goalId("g1").projectId("pB").workPackageId("mA").build();
        when(workPackageRepository.findById("mA"))
                .thenReturn(Optional.of(GoalWorkPackage.builder().id("mA").goalId("g1").projectId("pA").build()));

        consistency.afterProjectChange(todo);

        verify(goalProjectRepository).linkIfAbsent("g1", "pB");
        assertThat(todo.getWorkPackageId()).isNull();
    }

    @Test
    void workPackageOfTheNewProject_orWithoutProject_stays() {
        Todo fits = Todo.builder().id("t1").goalId("g1").projectId("pB").workPackageId("mB").build();
        Todo legacy = Todo.builder().id("t2").goalId("g1").projectId("pB").workPackageId("mLegacy").build();
        when(workPackageRepository.findById("mB"))
                .thenReturn(Optional.of(GoalWorkPackage.builder().id("mB").goalId("g1").projectId("pB").build()));
        when(workPackageRepository.findById("mLegacy"))
                .thenReturn(Optional.of(GoalWorkPackage.builder().id("mLegacy").goalId("g1").build()));

        consistency.afterProjectChange(fits);
        consistency.afterProjectChange(legacy);

        assertThat(fits.getWorkPackageId()).isEqualTo("mB");
        assertThat(legacy.getWorkPackageId()).isEqualTo("mLegacy");
    }

    @Test
    void projectRemoved_linksNothing_andLeavesAProjectWorkPackage() {
        Todo todo = Todo.builder().id("t").goalId("g1").projectId(null).workPackageId("mA").build();
        when(workPackageRepository.findById("mA"))
                .thenReturn(Optional.of(GoalWorkPackage.builder().id("mA").goalId("g1").projectId("pA").build()));

        consistency.afterProjectChange(todo);

        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
        assertThat(todo.getWorkPackageId()).as("ohne Projekt passt es in kein Arbeitspaket mit Projekt").isNull();
    }

    @Test
    void todoWithoutGoal_isLeftAlone() {
        Todo todo = Todo.builder().id("t").projectId("pB").build();

        consistency.afterProjectChange(todo);

        verifyNoInteractions(goalProjectRepository, workPackageRepository);
    }
}
