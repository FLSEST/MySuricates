package com.automation.service.goal;

import com.automation.dto.request.UpdateTodoPlanningRequest;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.Todo;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalProjectRepository;
import com.automation.repository.GoalRepository;
import com.automation.repository.TodoRepository;
import com.automation.service.todo.TodoResponseAssembler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Einordnung eines TODOs ueber {@code PATCH /todos/{id}/planning} (TODO dc0566f8, 259f4f66) – mit
 * den Regeln von TODO c60bf914 (Ziele Stufe 1: Ziel → Projekte → Arbeitspakete → TODOs, Meilensteine,
 * Gliederung und Zeitstrahl): Das Projekt des TODOs kommt ans Ziel, ein Arbeitspaket muss zum Projekt
 * des TODOs passen.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TodoPlanningServiceTest {

    @Mock private TodoRepository todoRepository;
    @Mock private GoalRepository goalRepository;
    @Mock private GoalWorkPackageRepository workPackageRepository;
    @Mock private TodoResponseAssembler responseAssembler;
    @Mock private GoalProjectRepository goalProjectRepository;

    private TodoPlanningService service;

    @BeforeEach
    void setUp() {
        service = new TodoPlanningService(todoRepository, goalRepository, workPackageRepository, responseAssembler,
                goalProjectRepository);
        when(todoRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(goalRepository.existsById("g1")).thenReturn(true);
    }

    @Test
    void goalOfAnotherProject_linksTheTodoProjectToTheGoal() {
        Todo todo = todo("pB", null, null);

        service.update("t", planning("g1", null));

        assertThat(todo.getGoalId()).isEqualTo("g1");
        verify(goalProjectRepository).linkIfAbsent("g1", "pB");
    }

    @Test
    void goalForATodoWithoutProject_linksNothing() {
        Todo todo = todo(null, null, null);

        service.update("t", planning("g1", null));

        assertThat(todo.getGoalId()).isEqualTo("g1");
        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
    }

    @Test
    void unknownGoal_is404_andLeavingTheGoalLinksNothing() {
        Todo todo = todo("pA", "g1", null);

        assertThatThrownBy(() -> service.update("t", planning("missing", null))).isInstanceOf(ResourceNotFoundException.class);
        service.update("t", planning("", null));

        assertThat(todo.getGoalId()).isNull();
        verify(goalProjectRepository, never()).linkIfAbsent(any(), any());
    }

    @Test
    void workPackageOfTheSameProject_isAssigned() {
        Todo todo = todo("pA", "g1", null);
        workPackage("mA", "pA");

        service.update("t", planning(null, "mA"));

        assertThat(todo.getWorkPackageId()).isEqualTo("mA");
    }

    @Test
    void workPackageOfAnotherProject_is400() {
        Todo todo = todo("pB", "g1", null);
        workPackage("mA", "pA");

        assertThatThrownBy(() -> service.update("t", planning(null, "mA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("pA").hasMessageContaining("pB");
        assertThat(todo.getWorkPackageId()).isNull();
        verify(todoRepository, never()).save(any());
    }

    @Test
    void todoWithoutProject_fitsNoWorkPackageWithProject_butALegacyOneWithout() {
        Todo todo = todo(null, "g1", null);
        workPackage("mA", "pA");
        workPackage("legacy", null);

        assertThatThrownBy(() -> service.update("t", planning(null, "mA")))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("kein Projekt");
        service.update("t", planning(null, "legacy"));

        assertThat(todo.getWorkPackageId()).isEqualTo("legacy");
    }

    @Test
    void goalAndWorkPackageInOneStep_goalFirst() {
        Todo todo = todo("pA", null, null);
        workPackage("mA", "pA");

        service.update("t", planning("g1", "mA"));

        assertThat(todo.getGoalId()).isEqualTo("g1");
        assertThat(todo.getWorkPackageId()).isEqualTo("mA");
        verify(goalProjectRepository).linkIfAbsent("g1", "pA");
    }

    /** Bis V92 hiess das Feld {@code milestoneId}; Aufrufer von vorher (Plugin, Skripte) schicken es weiter. */
    @Test
    void request_acceptsTheOldFieldNameAsAlias() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        assertThat(mapper.readValue("{\"milestoneId\":\"wp1\"}", UpdateTodoPlanningRequest.class).workPackageId())
                .isEqualTo("wp1");
        assertThat(mapper.readValue("{\"workPackageId\":\"wp2\"}", UpdateTodoPlanningRequest.class).workPackageId())
                .isEqualTo("wp2");
    }

    private Todo todo(String projectId, String goalId, String workPackageId) {
        Todo todo = Todo.builder().id("t").title("T").projectId(projectId).goalId(goalId).workPackageId(workPackageId).build();
        when(todoRepository.findById("t")).thenReturn(Optional.of(todo));
        return todo;
    }

    private void workPackage(String id, String projectId) {
        when(workPackageRepository.findById(id))
                .thenReturn(Optional.of(GoalWorkPackage.builder().id(id).goalId("g1").projectId(projectId).title(id).build()));
    }

    private static UpdateTodoPlanningRequest planning(String goalId, String workPackageId) {
        return new UpdateTodoPlanningRequest(goalId, null, null, null, null, null, null, workPackageId);
    }
}
