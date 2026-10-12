package com.automation.controller;

import com.automation.dto.response.DashboardReportAssistResponse;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.ModuleGuardInterceptor;
import com.automation.modules.ModuleRegistry;
import com.automation.security.RateLimitConfig;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dashboard.DashboardReportAssistService;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardReportAssistController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardReportAssistControllerTest {

    private static final String PATH = "/api/v1/dashboard/report-assist";

    @Autowired private MockMvc mockMvc;
    @Autowired private ModuleRegistry moduleRegistry;
    @MockitoBean private DashboardReportAssistService service;
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

    private static String body(String workflowId) {
        return "{\"workflowId\":\"" + workflowId + "\"}";
    }

    @Test
    void returns_the_validated_proposal() throws Exception {
        when(service.propose("wf-1")).thenReturn(new DashboardReportAssistResponse(
                "run-1", Instant.parse("2026-09-20T01:05:00Z"), "CLAUDE",
                new Result("Build", Result.Status.YELLOW, null, List.of("2 flaky"), "ok")));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("wf-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.runId").value("run-1"))
                .andExpect(jsonPath("$.runCompletedAt").value("2026-09-20T01:05:00Z"))
                .andExpect(jsonPath("$.provider").value("CLAUDE"))
                .andExpect(jsonPath("$.proposal.status").value("YELLOW"))
                .andExpect(jsonPath("$.proposal.items[0]").value("2 flaky"));
    }

    @Test
    void missing_workflow_id_is_passed_on_as_null_and_answers_400() throws Exception {
        when(service.propose(null)).thenThrow(new IllegalArgumentException("workflowId is required"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
    }

    @Test
    void missing_body_answers_400_without_calling_the_service() throws Exception {
        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());

        verify(service, never()).propose(any());
    }

    @Test
    void unknown_workflow_answers_404() throws Exception {
        when(service.propose("missing")).thenThrow(new ResourceNotFoundException("Workflow", "missing"));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("missing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void switched_off_or_unusable_proposal_answers_409_with_the_message() throws Exception {
        when(service.propose("wf-1")).thenThrow(new IllegalStateException("Dieser Workflow hat noch keinen Run."));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("wf-1")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("STATE_CONFLICT"))
                .andExpect(jsonPath("$.error").value("Dieser Workflow hat noch keinen Run."));
    }

    @Test
    void disabled_module_answers_404_module_disabled_without_calling_the_service() throws Exception {
        moduleRegistry.applyUiOverrides(Map.of(ModuleCatalog.DASHBOARD_REPORT, false));

        mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body("wf-1")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("MODULE_DISABLED"));

        verify(service, never()).propose(any());
    }

    @Test
    void get_is_not_offered_because_the_call_costs_tokens() throws Exception {
        mockMvc.perform(get(PATH)).andExpect(status().isMethodNotAllowed());

        verify(service, never()).propose(any());
    }
}
