package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.DashboardWorkflowResultResponse;
import com.automation.dto.response.DashboardWorkflowResultResponse.Result;
import com.automation.dto.response.DashboardWorkflowResultResponse.ResultState;
import com.automation.dto.response.DashboardWorkflowResultResponse.RunSummary;
import com.automation.entity.GateStatus;
import com.automation.entity.RunStatus;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.dashboard.DashboardWorkflowService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DashboardWorkflowController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class DashboardWorkflowControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private DashboardWorkflowService service;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void returns_the_latest_run_and_the_validated_contract() throws Exception {
        when(service.results(List.of("wf-a1", "wf-b1"))).thenReturn(List.of(
                new DashboardWorkflowResultResponse("wf-a1", "Nightly QA", true,
                        new RunSummary("run-1", RunStatus.COMPLETED, Instant.parse("2026-09-18T08:00:00Z"),
                                Instant.parse("2026-09-18T08:02:00Z"), 120_000L, GateStatus.GREEN),
                        ResultState.VALID,
                        new Result("Nightly", Result.Status.GREEN,
                                List.of(new Result.Kpi("Hunts", "1361", Result.Trend.UP)), List.of("ok"), "fine")),
                new DashboardWorkflowResultResponse("wf-b1", null, false, null, ResultState.MISSING, null)));

        mockMvc.perform(get("/api/v1/dashboard/workflow-results?workflowIds=wf-a1&workflowIds=wf-b1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowName").value("Nightly QA"))
                .andExpect(jsonPath("$[0].run.runId").value("run-1"))
                .andExpect(jsonPath("$[0].run.gateStatus").value("GREEN"))
                .andExpect(jsonPath("$[0].run.startedAt").value("2026-09-18T08:00:00Z"))
                .andExpect(jsonPath("$[0].resultState").value("VALID"))
                .andExpect(jsonPath("$[0].result.kpis[0].trend").value("UP"))
                .andExpect(jsonPath("$[1].available").value(false))
                .andExpect(jsonPath("$[1].result").doesNotExist())
                // Nothing that could carry step output or a link.
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("\"output\""))))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("http"))));
    }

    @Test
    void without_ids_it_answers_with_an_empty_list_and_asks_nothing() throws Exception {
        when(service.results(List.of())).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/dashboard/workflow-results"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }

    @Test
    void an_invalid_id_is_a_bad_request() throws Exception {
        when(service.results(anyList())).thenThrow(new IllegalArgumentException("workflowIds must match ^[a-z0-9]…"));

        mockMvc.perform(get("/api/v1/dashboard/workflow-results?workflowIds=../etc"))
                .andExpect(status().isBadRequest());
    }
}
