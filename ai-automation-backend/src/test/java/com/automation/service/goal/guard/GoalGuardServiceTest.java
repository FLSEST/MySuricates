package com.automation.service.goal.guard;

import com.automation.dto.projection.GoalTodoRow;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.GoalGuardRequest;
import com.automation.dto.request.UpdateScheduleRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Goal;
import com.automation.entity.Project;
import com.automation.entity.ScheduledWorkflow;
import com.automation.entity.Todo;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleRegistry;
import com.automation.repository.GoalRepository;
import com.automation.repository.ProjectRepository;
import com.automation.repository.TodoRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.goal.GoalService;
import com.automation.service.scheduler.SchedulerService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Ziel-Waechter: einmal anlegen, pausieren, wieder aufnehmen (TODO 259f4f66). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GoalGuardServiceTest {

    @Mock private GoalRepository goalRepository;
    @Mock private TodoRepository todoRepository;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowRepository workflowRepository;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowTemplateService templateService;
    @Mock private SchedulerService schedulerService;
    @Mock private GoalService goalService;
    @Mock private ModuleRegistry modules;

    @InjectMocks private GoalGuardService service;

    private Goal goal;
    private final Project projectA = Project.builder().id("pa").name("A").build();
    private final Project projectB = Project.builder().id("pb").name("B").build();

    @BeforeEach
    void setUp() {
        goal = Goal.builder().id("g1").title("Open-Source-Launch").build();
        when(goalRepository.findById("g1")).thenReturn(Optional.of(goal));
        when(goalRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(modules.isEnabled(ModuleCatalog.DASHBOARD_REPORT)).thenReturn(true);
        when(templateService.findById(GoalGuardService.TEMPLATE_ID)).thenReturn(template());
        when(workflowService.create(any(), any())).thenAnswer(inv -> Workflow.builder().id("wf-new")
                .name(inv.<CreateWorkflowRequest>getArgument(0).name()).build());
        when(schedulerService.create(anyString(), any(), anyString(), anyString(), anyString(), anyMap(), anyString(),
                any(), any(), any(), any(), anyString(), anyBoolean()))
                .thenReturn(ScheduledWorkflow.builder().id("s-new").build());
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(
                row("t1", "pb"), row("t2", "pa"), row("t3", "pb"), row("t4", null)));
        when(projectRepository.findAllById(any())).thenReturn(List.of(projectA, projectB));
    }

    @Test
    void firstSwitchOn_createsWorkflowFromTheTemplateAndADailySchedule_andRemembersTheWorkflow() {
        service.setGuard("g1", new GoalGuardRequest(true, null, null, "de"));

        ArgumentCaptor<CreateWorkflowRequest> created = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(created.capture(), eq(ChangeSource.GOAL_GUARD));
        CreateWorkflowRequest request = created.getValue();
        assertThat(request.projectId()).as("Projekt mit den meisten TODOs des Ziels").isEqualTo("pb");
        assertThat(request.name()).isEqualTo("Ziel-Wächter: Open-Source-Launch");
        assertThat(request.templateId()).isEqualTo("goal-guard");
        assertThat(request.model()).as("kein Modell").isNull();
        assertThat(request.defaultContext()).containsEntry("goal_id", "g1").containsEntry("goal_locale", "de");
        assertThat(request.blocks()).extracting(BlockDefinition::getType).containsExactly("shell", "dashboard-report");

        verify(schedulerService).create(eq("wf-new"), isNull(), eq("WORKFLOW"), eq("0 0 8 * * *"), anyString(),
                eq(Map.of("goal_id", "g1")), eq("CRON"), isNull(), isNull(), isNull(), isNull(), eq("SKIP"), eq(false));
        assertThat(goal.getGuardWorkflowId()).isEqualTo("wf-new");
        verify(goalRepository).save(goal);
        verify(goalService).get("g1");
    }

    @Test
    void firstSwitchOn_usesTheGivenProjectAndCron_andEnglishNames() {
        when(projectRepository.findById("pa")).thenReturn(Optional.of(projectA));

        service.setGuard("g1", new GoalGuardRequest(true, "pa", "0 30 7 * * MON-FRI", "en"));

        ArgumentCaptor<CreateWorkflowRequest> created = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(created.capture(), eq(ChangeSource.GOAL_GUARD));
        assertThat(created.getValue().projectId()).isEqualTo("pa");
        assertThat(created.getValue().name()).isEqualTo("Goal guard: Open-Source-Launch");
        assertThat(created.getValue().blocks()).extracting(BlockDefinition::getName).containsExactly("log_status", "report");
        verify(schedulerService).create(eq("wf-new"), isNull(), eq("WORKFLOW"), eq("0 30 7 * * MON-FRI"), anyString(),
                anyMap(), eq("CRON"), isNull(), isNull(), isNull(), isNull(), eq("SKIP"), eq(false));
    }

    @Test
    void firstSwitchOn_withoutTodosInAnyProject_takesTheFirstActiveProject() {
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of(row("t1", null)));
        Project older = Project.builder().id("old").name("Old").createdAt(Instant.parse("2026-01-01T00:00:00Z")).build();
        Project newer = Project.builder().id("new").name("New").createdAt(Instant.parse("2026-05-01T00:00:00Z")).build();
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of(newer, older));

        service.setGuard("g1", new GoalGuardRequest(true, null, null, null));

        ArgumentCaptor<CreateWorkflowRequest> created = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
        verify(workflowService).create(created.capture(), eq(ChangeSource.GOAL_GUARD));
        assertThat(created.getValue().projectId()).isEqualTo("old");
    }

    @Test
    void firstSwitchOn_withoutAnyProject_isRefused() {
        when(todoRepository.findGoalRows("g1")).thenReturn(List.of());
        when(projectRepository.findByArchivedAtIsNull()).thenReturn(List.of());

        assertThatThrownBy(() -> service.setGuard("g1", new GoalGuardRequest(true, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workflowService, never()).create(any(), any());
    }

    @Test
    void switchOn_withAnInvalidCron_orAnUnknownProject_isRefused() {
        when(projectRepository.findById("gone")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setGuard("g1", new GoalGuardRequest(true, null, "jeden Morgen", null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.setGuard("g1", new GoalGuardRequest(true, "gone", null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(workflowService, never()).create(any(), any());
    }

    @Test
    void switchOn_whileTheDashboardReportModuleIsOff_isRefused() {
        when(modules.isEnabled(ModuleCatalog.DASHBOARD_REPORT)).thenReturn(false);

        assertThatThrownBy(() -> service.setGuard("g1", new GoalGuardRequest(true, null, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verify(workflowService, never()).create(any(), any());
    }

    @Test
    void switchOff_pausesTheSchedule_andKeepsTheWorkflow() {
        goal.setGuardWorkflowId("wf-1");
        when(schedulerService.findByWorkflowId("wf-1")).thenReturn(List.of(schedule("s1", true)));

        service.setGuard("g1", new GoalGuardRequest(false, null, null, null));

        verify(schedulerService).disable("s1");
        verify(workflowService, never()).delete(anyString());
        verify(workflowService, never()).create(any(), any());
        assertThat(goal.getGuardWorkflowId()).isEqualTo("wf-1");
    }

    @Test
    void switchOff_neverSwitchedOn_changesNothing() {
        service.setGuard("g1", new GoalGuardRequest(false, null, null, null));

        verify(schedulerService, never()).disable(anyString());
        verify(goalService).get("g1");
    }

    @Test
    void secondSwitchOn_reusesWorkflowAndSchedule() {
        goal.setGuardWorkflowId("wf-1");
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(Workflow.builder().id("wf-1").build()));
        when(schedulerService.findByWorkflowId("wf-1")).thenReturn(List.of(schedule("s1", false)));

        service.setGuard("g1", new GoalGuardRequest(true, null, null, null));

        verify(schedulerService).enable("s1");
        verify(workflowService, never()).create(any(), any());
        verify(schedulerService, never()).create(anyString(), any(), anyString(), anyString(), anyString(), anyMap(),
                anyString(), any(), any(), any(), any(), anyString(), anyBoolean());
        verify(schedulerService, never()).update(anyString(), any());
    }

    @Test
    void secondSwitchOn_withANewCron_updatesTheSchedule() {
        goal.setGuardWorkflowId("wf-1");
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(Workflow.builder().id("wf-1").build()));
        when(schedulerService.findByWorkflowId("wf-1")).thenReturn(List.of(schedule("s1", true)));

        service.setGuard("g1", new GoalGuardRequest(true, null, "0 0 18 * * *", null));

        ArgumentCaptor<UpdateScheduleRequest> update = ArgumentCaptor.forClass(UpdateScheduleRequest.class);
        verify(schedulerService).update(eq("s1"), update.capture());
        assertThat(update.getValue().cronExpression()).isEqualTo("0 0 18 * * *");
        verify(schedulerService, never()).enable(anyString());
    }

    @Test
    void switchOn_afterTheWorkflowWasDeleted_createsANewOne() {
        goal.setGuardWorkflowId("wf-deleted");
        when(workflowRepository.findById("wf-deleted")).thenReturn(Optional.empty());

        service.setGuard("g1", new GoalGuardRequest(true, null, null, null));

        verify(workflowService).create(any(), eq(ChangeSource.GOAL_GUARD));
        assertThat(goal.getGuardWorkflowId()).isEqualTo("wf-new");
    }

    @Test
    void unknownGoal_is404() {
        when(goalRepository.findById("nope")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setGuard("nope", new GoalGuardRequest(true, null, null, null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private static ScheduledWorkflow schedule(String id, boolean enabled) {
        return ScheduledWorkflow.builder().id(id).enabled(enabled).cronExpression(GoalGuardService.DEFAULT_CRON).build();
    }

    private static WorkflowTemplate template() {
        String source = """
                {"id":"goal-guard","i18n":["de","en"],"instructions":null,"blocks":[
                  {"type":"shell","name":{"de":"stand_protokollieren","en":"log_status"},"config":{"command":"true"}},
                  {"type":"dashboard-report","name":{"de":"melden","en":"report"},"config":{"status":"GREEN"}}]}
                """;
        return WorkflowTemplate.builder()
                .id("goal-guard")
                .name(Map.of("de", "Ziel-Wächter", "en", "Goal guard"))
                .description(Map.of("de", "Beschreibung", "en", "Description"))
                .icon("🎯")
                .builtIn(true)
                .i18nSource(source)
                .blocks(List.of())
                .build();
    }

    private static GoalTodoRow row(String id, String projectId) {
        Instant now = Instant.now();
        return new GoalTodoRow(id, id, Todo.Status.OPEN, Todo.Priority.MEDIUM, projectId, null, null, null, null, null,
                null, null, null, now, now, null, null);
    }
}
