package com.automation.controller;

import com.automation.dto.request.CreateGoalMilestoneRequest;
import com.automation.dto.request.CreateGoalRequest;
import com.automation.dto.request.CreateGoalWorkPackageRequest;
import com.automation.dto.request.GoalGuardRequest;
import com.automation.dto.request.UpdateGoalMilestoneRequest;
import com.automation.dto.request.UpdateGoalRequest;
import com.automation.dto.request.UpdateGoalWorkPackageRequest;
import com.automation.dto.response.GoalDetailResponse;
import com.automation.dto.response.GoalGuardResponse;
import com.automation.dto.response.GoalMilestoneResponse;
import com.automation.dto.response.GoalProgressResponse;
import com.automation.dto.response.GoalRelationsResponse;
import com.automation.dto.response.GoalResponse;
import com.automation.dto.response.GoalWorkPackageResponse;
import com.automation.dto.response.PageResponse;
import com.automation.entity.Goal;
import com.automation.entity.GoalDueState;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleGuardInterceptor;
import com.automation.modules.ModuleRegistry;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.goal.GoalCandidateService;
import com.automation.service.goal.GoalMembershipService;
import com.automation.service.goal.GoalMilestoneService;
import com.automation.service.goal.GoalProjectService;
import com.automation.service.goal.GoalRelationsService;
import com.automation.service.goal.GoalService;
import com.automation.service.goal.GoalWorkPackageService;
import com.automation.service.goal.guard.GoalGuardService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.handler.MappedInterceptor;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoalController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class GoalControllerTest {

    private static final String PATH = "/api/v1/goals";
    private static final Instant AT = Instant.parse("2026-10-07T08:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ModuleRegistry moduleRegistry;
    @MockitoBean private GoalService goalService;
    @MockitoBean private GoalMembershipService membershipService;
    @MockitoBean private GoalRelationsService relationsService;
    @MockitoBean private GoalWorkPackageService workPackageService;
    @MockitoBean private GoalMilestoneService milestoneService;
    @MockitoBean private GoalProjectService projectService;
    @MockitoBean private GoalCandidateService candidateService;
    @MockitoBean private GoalGuardService guardService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    /** Real registry and the real module guard, so the 404 for a disabled module is the production path. */
    @TestConfiguration
    static class Modules {
        @Bean
        ModuleRegistry moduleRegistry() {
            return ModuleRegistry.of(Map.of("preset", "full", "ui-editable", "true"), Map.of());
        }

        @Bean
        MappedInterceptor moduleGuard(ModuleRegistry registry, ObjectMapper objectMapper) {
            return new MappedInterceptor(new String[]{"/api/**"}, null, new ModuleGuardInterceptor(registry, objectMapper));
        }
    }

    @AfterEach
    void resetModules() {
        moduleRegistry.applyUiOverrides(Map.of());
    }

    private static GoalResponse goal(String id) {
        return new GoalResponse(id, "Release", "desc", Goal.Status.ACTIVE, AT, AT, AT,
                GoalProgressResponse.of(1, 0, 0, 1, 0), GoalGuardResponse.NONE,
                List.of(new GoalResponse.ProjectRef("p-1", "Shop")), null);
    }

    private static GoalDetailResponse detail(String id) {
        return new GoalDetailResponse(goal(id), List.of(), List.of(), List.of(), List.of(), List.of());
    }

    private static GoalWorkPackageResponse workPackage(String id) {
        return new GoalWorkPackageResponse(id, "g-1", "p-1", "Beta", null, AT, 0, GoalDueState.OPEN, 2, 1, 0, null,
                false, AT, AT, 0);
    }

    private static GoalMilestoneResponse milestone(String id) {
        return new GoalMilestoneResponse(id, "g-1", "Launch", AT, GoalDueState.OPEN, List.of("wp-1"), 1, 0, null,
                false, AT, AT);
    }

    private static String json(String body) {
        return body.replace('\'', '"');
    }

    @Test
    void listGoals_passesStatusFilterAndProject() throws Exception {
        when(goalService.list(List.of(Goal.Status.ACTIVE, Goal.Status.DONE), "p-1")).thenReturn(List.of(goal("g-1")));

        mockMvc.perform(get(PATH).param("status", "ACTIVE", "DONE").param("projectId", "p-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("g-1"))
                .andExpect(jsonPath("$[0].projects[0].name").value("Shop"));
    }

    @Test
    void listGoals_withoutParametersPassesNulls() throws Exception {
        when(goalService.list(null, null)).thenReturn(List.of());

        mockMvc.perform(get(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$").isEmpty());

        verify(goalService).list(null, null);
    }

    @Test
    void listGoals_unknownStatusIs400() throws Exception {
        mockMvc.perform(get(PATH).param("status", "SOMEDAY")).andExpect(status().isBadRequest());

        verifyNoInteractions(goalService);
    }

    @Test
    void createGoal_returns201() throws Exception {
        when(goalService.create(new CreateGoalRequest("Release", null, "2026-12-01", List.of("p-1"))))
                .thenReturn(goal("g-new"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'title':'Release','targetDate':'2026-12-01','projectIds':['p-1']}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("g-new"));
    }

    @Test
    void createGoal_blankTitleIs400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(json("{'title':' '}")))
                .andExpect(status().isBadRequest());

        verify(goalService, never()).create(any());
    }

    @Test
    void createGoal_unknownProjectIs404() throws Exception {
        when(goalService.create(any())).thenThrow(new ResourceNotFoundException("Project", "ghost"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'title':'Release','projectIds':['ghost']}")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void getGoal_returnsTheDetail_andUnknownIs404() throws Exception {
        when(goalService.detail("g-1")).thenReturn(detail("g-1"));
        when(goalService.detail("ghost")).thenThrow(new ResourceNotFoundException("Goal", "ghost"));

        mockMvc.perform(get(PATH + "/g-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.id").value("g-1"))
                .andExpect(jsonPath("$.goal.progress.done").value(1))
                .andExpect(jsonPath("$.todos").isArray());
        mockMvc.perform(get(PATH + "/ghost")).andExpect(status().isNotFound());
    }

    @Test
    void getRelations_passesTheIncludedIds() throws Exception {
        when(relationsService.relations("g-1", List.of("t-1", "t-2")))
                .thenReturn(new GoalRelationsResponse("g-1", 2, List.of()));

        mockMvc.perform(get(PATH + "/g-1/relations").param("include", "t-1", "t-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeTriggers").value(2));
        mockMvc.perform(get(PATH + "/g-1/relations")).andExpect(status().isOk());

        verify(relationsService).relations("g-1", null);
    }

    @Test
    void updateGoal_passesTheRequest() throws Exception {
        when(goalService.update("g-1", new UpdateGoalRequest(null, "", Goal.Status.DONE, null))).thenReturn(goal("g-1"));

        mockMvc.perform(put(PATH + "/g-1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'description':'','status':'DONE'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("g-1"));
    }

    @Test
    void updateGoal_invalidDateFromTheServiceIs400() throws Exception {
        when(goalService.update(anyString(), any())).thenThrow(new IllegalArgumentException("Invalid date: morgen"));

        mockMvc.perform(put(PATH + "/g-1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'targetDate':'morgen'}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void deleteGoal_pausesTheGuardFirstAndAnswers204() throws Exception {
        mockMvc.perform(delete(PATH + "/g-1")).andExpect(status().isNoContent());

        InOrder order = inOrder(guardService, goalService);
        order.verify(guardService).pauseForDeletedGoal("g-1");
        order.verify(goalService).delete("g-1");
    }

    @Test
    void candidates_usesTheDefaults() throws Exception {
        when(candidateService.candidates("g-1", null, null, false, 0, 20))
                .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));

        mockMvc.perform(get(PATH + "/g-1/candidates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.size").value(20));
    }

    @Test
    void candidates_passesFilterScopeAndPage() throws Exception {
        when(candidateService.candidates("g-1", "login", "ux", true, 2, 5))
                .thenReturn(new PageResponse<>(List.of(), 2, 5, 11, 3));

        mockMvc.perform(get(PATH + "/g-1/candidates").param("q", "login").param("tag", "ux")
                        .param("allProjects", "true").param("page", "2").param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(11));
    }

    @Test
    void assignAndReorderTodos_passTheIds() throws Exception {
        when(membershipService.assign("g-1", List.of("t-1"))).thenReturn(detail("g-1"));
        when(membershipService.reorder("g-1", List.of("t-2", "t-1"))).thenReturn(detail("g-1"));

        mockMvc.perform(post(PATH + "/g-1/todos").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'todoIds':['t-1']}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.goal.id").value("g-1"));
        mockMvc.perform(put(PATH + "/g-1/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'todoIds':['t-2','t-1']}")))
                .andExpect(status().isOk());
    }

    @Test
    void todoIds_areRequired_butMayBeEmpty() throws Exception {
        when(membershipService.reorder("g-1", List.of())).thenReturn(detail("g-1"));

        mockMvc.perform(post(PATH + "/g-1/todos").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(PATH + "/g-1/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'todoIds':[]}")))
                .andExpect(status().isOk());

        verify(membershipService, never()).assign(anyString(), any());
    }

    @Test
    void reorderTodos_foreignTodoIs400() throws Exception {
        when(membershipService.reorder("g-1", List.of("foreign")))
                .thenThrow(new IllegalArgumentException("TODO foreign gehoert nicht zum Ziel"));

        mockMvc.perform(put(PATH + "/g-1/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'todoIds':['foreign']}")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void projects_addReorderAndRemove() throws Exception {
        when(projectService.add("g-1", "p-2")).thenReturn(detail("g-1"));
        when(projectService.reorder("g-1", List.of("p-2", "p-1"))).thenReturn(detail("g-1"));

        mockMvc.perform(post(PATH + "/g-1/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'projectId':'p-2'}")))
                .andExpect(status().isOk());
        mockMvc.perform(put(PATH + "/g-1/projects/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'ids':['p-2','p-1']}")))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PATH + "/g-1/projects/p-2")).andExpect(status().isNoContent());
        mockMvc.perform(delete(PATH + "/g-1/projects/p-3").param("cascade", "true")).andExpect(status().isNoContent());

        verify(projectService).remove("g-1", "p-2", false);
        verify(projectService).remove("g-1", "p-3", true);
    }

    @Test
    void projects_blankIdOrEmptyOrderIs400() throws Exception {
        mockMvc.perform(post(PATH + "/g-1/projects").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'projectId':''}")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put(PATH + "/g-1/projects/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'ids':[]}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(projectService);
    }

    @Test
    void removeProject_stillInUseIs409() throws Exception {
        doThrow(new IllegalStateException("Projekt hat noch Pakete")).when(projectService).remove("g-1", "p-1", false);

        mockMvc.perform(delete(PATH + "/g-1/projects/p-1"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void workPackages_listCreateReorderUpdateDelete() throws Exception {
        when(workPackageService.list("g-1")).thenReturn(List.of(workPackage("wp-1")));
        when(workPackageService.create("g-1", new CreateGoalWorkPackageRequest("Beta", "p-1", null, null)))
                .thenReturn(workPackage("wp-new"));
        when(workPackageService.reorder("g-1", List.of("wp-2", "wp-1"))).thenReturn(List.of(workPackage("wp-2")));
        when(workPackageService.update("g-1", "wp-1", new UpdateGoalWorkPackageRequest(null, null, "", "")))
                .thenReturn(workPackage("wp-1"));

        mockMvc.perform(get(PATH + "/g-1/work-packages"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].state").value("OPEN"));
        mockMvc.perform(post(PATH + "/g-1/work-packages").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'title':'Beta','projectId':'p-1'}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("wp-new"));
        mockMvc.perform(put(PATH + "/g-1/work-packages/order").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'ids':['wp-2','wp-1']}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("wp-2"));
        mockMvc.perform(put(PATH + "/g-1/work-packages/wp-1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'startDate':'','endDate':''}")))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PATH + "/g-1/work-packages/wp-1")).andExpect(status().isNoContent());

        verify(workPackageService).delete("g-1", "wp-1");
    }

    @Test
    void createWorkPackage_withoutTitleIs400() throws Exception {
        mockMvc.perform(post(PATH + "/g-1/work-packages").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'projectId':'p-1'}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(workPackageService);
    }

    @Test
    void milestones_listCreateUpdateDelete() throws Exception {
        when(milestoneService.list("g-1")).thenReturn(List.of(milestone("m-1")));
        when(milestoneService.create("g-1", new CreateGoalMilestoneRequest("Launch", "2026-12-01", List.of("wp-1"))))
                .thenReturn(milestone("m-new"));
        when(milestoneService.update("g-1", "m-1", new UpdateGoalMilestoneRequest(null, null, null, true)))
                .thenReturn(milestone("m-1"));

        mockMvc.perform(get(PATH + "/g-1/milestones"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workPackageIds[0]").value("wp-1"));
        mockMvc.perform(post(PATH + "/g-1/milestones").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'title':'Launch','dueDate':'2026-12-01','workPackageIds':['wp-1']}")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("m-new"));
        mockMvc.perform(put(PATH + "/g-1/milestones/m-1").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'reached':true}")))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PATH + "/g-1/milestones/m-1")).andExpect(status().isNoContent());

        verify(milestoneService).delete("g-1", "m-1");
    }

    @Test
    void createMilestone_withoutDueDateIs400() throws Exception {
        mockMvc.perform(post(PATH + "/g-1/milestones").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'title':'Launch'}")))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(milestoneService);
    }

    @Test
    void setGuard_passesTheRequest_andRequiresEnabled() throws Exception {
        when(guardService.setGuard("g-1", new GoalGuardRequest(true, "p-1", "0 0 7 * * *", "de")))
                .thenReturn(goal("g-1"));

        mockMvc.perform(post(PATH + "/g-1/guard").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'enabled':true,'projectId':'p-1','cron':'0 0 7 * * *','locale':'de'}")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("g-1"));
        mockMvc.perform(post(PATH + "/g-1/guard").contentType(MediaType.APPLICATION_JSON)
                        .content(json("{'cron':'0 0 7 * * *'}")))
                .andExpect(status().isBadRequest());

        verify(guardService).setGuard(anyString(), any());
    }

    @Test
    void disabledModuleAnswers404ModuleDisabledWithoutCallingAService() throws Exception {
        moduleRegistry.applyUiOverrides(Map.of(ModuleCatalog.GOALS, false));

        mockMvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));
        mockMvc.perform(delete(PATH + "/g-1")).andExpect(status().isNotFound());

        verifyNoInteractions(goalService, guardService);
    }
}
