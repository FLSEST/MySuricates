package com.automation.controller;

import com.automation.dto.request.AttachGoalWorkflowRequest;
import com.automation.dto.response.GoalWorkflowResponse;
import com.automation.entity.GoalWorkflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleGuardInterceptor;
import com.automation.modules.ModuleRegistry;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.goal.GoalWorkflowService;
import com.automation.service.system.ApiKeyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(GoalWorkflowController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class GoalWorkflowControllerTest {

    private static final String PATH = "/api/v1/goals/g-1/workflows";
    private static final Instant AT = Instant.parse("2026-10-07T08:00:00Z");

    @Autowired private MockMvc mockMvc;
    @Autowired private ModuleRegistry moduleRegistry;
    @MockitoBean private GoalWorkflowService service;
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

    private static GoalWorkflowResponse attachment(String id, GoalWorkflow.Role role, boolean guard) {
        return new GoalWorkflowResponse(id, "g-1", null, "wf-1", "Build", "p-1", role, guard, AT);
    }

    @Test
    void list_returnsTheAttachmentsWithTheGuardFirst() throws Exception {
        when(service.list("g-1")).thenReturn(List.of(attachment("guard", GoalWorkflow.Role.STATUS, true),
                attachment("a-1", GoalWorkflow.Role.WORKER, false)));

        mockMvc.perform(get(PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("guard"))
                .andExpect(jsonPath("$[0].guard").value(true))
                .andExpect(jsonPath("$[1].role").value("WORKER"))
                .andExpect(jsonPath("$[1].workflowName").value("Build"));
    }

    @Test
    void attach_returns201() throws Exception {
        when(service.attach("g-1", new AttachGoalWorkflowRequest("wf-1", "wp-1", "STATUS")))
                .thenReturn(attachment("a-new", GoalWorkflow.Role.STATUS, false));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workflowId\":\"wf-1\",\"workPackageId\":\"wp-1\",\"role\":\"STATUS\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value("a-new"))
                .andExpect(jsonPath("$.role").value("STATUS"));
    }

    @Test
    void attach_withoutWorkflowIdIs400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"WORKER\"}"))
                .andExpect(status().isBadRequest());

        verify(service, never()).attach(anyString(), any());
    }

    @Test
    void attach_errorsOfTheServiceKeepTheirStatus() throws Exception {
        when(service.attach("g-1", new AttachGoalWorkflowRequest("ghost", null, null)))
                .thenThrow(new ResourceNotFoundException("Workflow", "ghost"));
        when(service.attach("g-1", new AttachGoalWorkflowRequest("wf-1", null, null)))
                .thenThrow(new IllegalStateException("Schon angehaengt"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"workflowId\":\"ghost\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{\"workflowId\":\"wf-1\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"));
    }

    @Test
    void updateRole_passesTheRole() throws Exception {
        when(service.updateRole("g-1", "a-1", "STATUS")).thenReturn(attachment("a-1", GoalWorkflow.Role.STATUS, false));

        mockMvc.perform(patch(PATH + "/a-1").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"STATUS\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("STATUS"));
    }

    @Test
    void updateRole_blankRoleIs400_andTheGuardEntryIsRefused() throws Exception {
        when(service.updateRole("g-1", "guard", "WORKER"))
                .thenThrow(new IllegalArgumentException("Der Waechter hat einen eigenen Schalter"));

        mockMvc.perform(patch(PATH + "/a-1").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(patch(PATH + "/guard").contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"WORKER\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));

        verify(service, never()).updateRole("g-1", "a-1", "");
    }

    @Test
    void detach_answers204_andTheGuardIs400() throws Exception {
        doThrow(new IllegalArgumentException("guard")).when(service).detach("g-1", "guard");

        mockMvc.perform(delete(PATH + "/a-1")).andExpect(status().isNoContent());
        mockMvc.perform(delete(PATH + "/guard")).andExpect(status().isBadRequest());

        verify(service).detach("g-1", "a-1");
    }

    @Test
    void disabledModuleAnswers404ModuleDisabled() throws Exception {
        moduleRegistry.applyUiOverrides(Map.of(ModuleCatalog.GOALS, false));

        mockMvc.perform(get(PATH))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));

        verifyNoInteractions(service);
    }
}
