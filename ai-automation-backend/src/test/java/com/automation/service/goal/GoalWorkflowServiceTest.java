package com.automation.service.goal;

import com.automation.dto.projection.GoalWorkflowRow;
import com.automation.dto.request.AttachGoalWorkflowRequest;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalWorkflowResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalWorkPackage;
import com.automation.entity.GoalWorkflow;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.GoalRepository;
import com.automation.repository.GoalWorkPackageRepository;
import com.automation.repository.GoalWorkflowRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Workflows am Ziel oder Arbeitspaket (TODO 52bc1e8c Ziele Stufe 3: Workflows am Ziel oder Teilziel
 * anzeigen, starten und als Stand nutzen): Reihenfolge, Waechter-Eintrag und die 400/404/409-Regeln.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalWorkflowServiceTest {

    private static final Instant T = Instant.parse("2026-09-28T08:00:00Z");

    @Mock private GoalRepository goalRepository;
    @Mock private GoalWorkPackageRepository workPackageRepository;
    @Mock private GoalWorkflowRepository goalWorkflowRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private GoalGuardLookup guardLookup;

    private GoalWorkflowService service;
    private Goal goal;

    @BeforeEach
    void setUp() {
        service = new GoalWorkflowService(goalRepository, workPackageRepository, goalWorkflowRepository,
                workflowRepository, guardLookup);
        goal = Goal.builder().id("g1").title("Release").build();
        when(goalRepository.findById("g1")).thenReturn(Optional.of(goal));
        when(goalRepository.existsById("g1")).thenReturn(true);
        when(goalWorkflowRepository.save(any())).thenAnswer(inv -> {
            GoalWorkflow a = inv.getArgument(0);
            if (a.getId() == null) a.setId("a-new");
            return a;
        });
    }

    @Test
    void list_goalLevelFirst_thenPackageOrder_thenName_withoutGuardWhenNoneIsSet() {
        when(goalWorkflowRepository.findRows("g1")).thenReturn(List.of(
                row("a1", "wpB", "Zeta", 0, 1),
                row("a2", null, "beta", null, null),
                row("a3", "wpA", "Alpha", 0, 0),
                row("a4", "wpB", "Alpha", 0, 1),
                row("a5", null, "Alpha", null, null),
                row("a6", "wpX", "Aaa", null, null)));

        List<GoalWorkflowResponse> list = service.list("g1");

        assertThat(list).extracting(GoalWorkflowResponse::id).containsExactly("a5", "a2", "a3", "a4", "a1", "a6");
        assertThat(list).noneMatch(GoalWorkflowResponse::guard);
        verifyNoInteractions(guardLookup);
    }

    @Test
    void list_anEnabledGuardComesFirst_aSwitchedOffOrDeletedOneNot() {
        goal.setGuardWorkflowId("wf-guard");
        Workflow guardWorkflow = workflow("wf-guard", "Ziel-Wächter: Release");
        when(workflowRepository.findById("wf-guard")).thenReturn(Optional.of(guardWorkflow));
        when(goalWorkflowRepository.findRows("g1")).thenReturn(List.of(row("a1", null, "Arbeiter", null, null)));
        when(guardLookup.of(goal)).thenReturn(new GoalGuardResponse(true, "wf-guard", "s1", T));

        List<GoalWorkflowResponse> list = service.list("g1");

        assertThat(list).extracting(GoalWorkflowResponse::id).containsExactly("guard", "a1");
        GoalWorkflowResponse guard = list.get(0);
        assertThat(guard.guard()).isTrue();
        assertThat(guard.role()).isEqualTo(GoalWorkflow.Role.STATUS);
        assertThat(guard.workflowName()).isEqualTo("Ziel-Wächter: Release");
        assertThat(guard.workflowProjectId()).isEqualTo("p1");
        assertThat(guard.workPackageId()).isNull();

        when(guardLookup.of(goal)).thenReturn(new GoalGuardResponse(false, "wf-guard", "s1", null));
        assertThat(service.list("g1")).extracting(GoalWorkflowResponse::id).containsExactly("a1");
        when(guardLookup.of(goal)).thenReturn(new GoalGuardResponse(true, "wf-guard", "s1", T));
        when(workflowRepository.findById("wf-guard")).thenReturn(Optional.empty());
        assertThat(service.list("g1")).as("Waechter-Workflow geloescht").extracting(GoalWorkflowResponse::id).containsExactly("a1");
    }

    @Test
    void list_unknownGoal_is404() {
        when(goalRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list("nope")).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void attach_toAPackageOfTheGoal_withDefaultRole() {
        when(workflowRepository.findById("wf1")).thenReturn(Optional.of(workflow("wf1", "Bauen")));
        when(workPackageRepository.findById("wp1")).thenReturn(Optional.of(GoalWorkPackage.builder().id("wp1").goalId("g1").build()));

        GoalWorkflowResponse attached = service.attach("g1", new AttachGoalWorkflowRequest("wf1", " wp1 ", null));

        ArgumentCaptor<GoalWorkflow> saved = ArgumentCaptor.forClass(GoalWorkflow.class);
        verify(goalWorkflowRepository).save(saved.capture());
        assertThat(saved.getValue().getWorkPackageId()).isEqualTo("wp1");
        assertThat(saved.getValue().getRole()).isEqualTo(GoalWorkflow.Role.WORKER);
        assertThat(attached.id()).isEqualTo("a-new");
        assertThat(attached.workflowName()).isEqualTo("Bauen");
        assertThat(attached.workflowProjectId()).isEqualTo("p1");
        assertThat(attached.guard()).isFalse();
    }

    @Test
    void attach_foreignPackage400_unknownWorkflow404_duplicate409_badRole400_unknownGoal404() {
        when(workflowRepository.findById("wf1")).thenReturn(Optional.of(workflow("wf1", "Bauen")));
        when(workflowRepository.findById("gone")).thenReturn(Optional.empty());
        when(workPackageRepository.findById("fremd")).thenReturn(Optional.of(GoalWorkPackage.builder().id("fremd").goalId("g2").build()));
        when(goalWorkflowRepository.existsAttachment("g1", null, "wf1")).thenReturn(true);
        when(goalRepository.existsById("nope")).thenReturn(false);

        assertThatThrownBy(() -> service.attach("g1", new AttachGoalWorkflowRequest("wf1", "fremd", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attach("g1", new AttachGoalWorkflowRequest("gone", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.attach("g1", new AttachGoalWorkflowRequest("wf1", null, "STATUS")))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> service.attach("g1", new AttachGoalWorkflowRequest("wf1", null, "BOSS")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.attach("nope", new AttachGoalWorkflowRequest("wf1", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(goalWorkflowRepository, never()).save(any());
    }

    @Test
    void updateRole_andDetach_refuseTheGuardAndForeignAttachments() {
        GoalWorkflow attachment = GoalWorkflow.builder().id("a1").goalId("g1").workflowId("wf1").build();
        when(goalWorkflowRepository.findById("a1")).thenReturn(Optional.of(attachment));
        when(workflowRepository.findById("wf1")).thenReturn(Optional.of(workflow("wf1", "Bauen")));

        assertThat(service.updateRole("g1", "a1", "status").role()).isEqualTo(GoalWorkflow.Role.STATUS);
        assertThatThrownBy(() -> service.updateRole("g1", "a1", " ")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.updateRole("g1", GoalWorkflowService.GUARD_ID, "WORKER"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detach("g1", GoalWorkflowService.GUARD_ID)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.detach("g2", "a1")).isInstanceOf(ResourceNotFoundException.class);
        verify(goalWorkflowRepository, never()).delete(any());

        service.detach("g1", "a1");
        verify(goalWorkflowRepository).delete(attachment);
    }

    private static GoalWorkflowRow row(String id, String workPackageId, String name, Integer projectPosition, Integer packagePosition) {
        return new GoalWorkflowRow(id, "g1", workPackageId, "wf-" + id, name, "p1", GoalWorkflow.Role.WORKER, T,
                packagePosition, projectPosition);
    }

    private static Workflow workflow(String id, String name) {
        return Workflow.builder().id(id).name(name).project(Project.builder().id("p1").name("P").build()).build();
    }
}
