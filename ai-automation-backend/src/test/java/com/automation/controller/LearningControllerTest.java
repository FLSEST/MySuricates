package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.DashboardResponse;
import com.automation.dto.response.PlatformStatsResponse;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.learning.LearningService;
import com.automation.service.learning.PlatformStatsService;
import com.automation.service.system.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.automation.service.activitylog.ActivityLogService;

@WebMvcTest(LearningController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class LearningControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LearningService learningService;
    @MockBean
    private PlatformStatsService platformStatsService;
    @MockBean
    private ActivityLogService activityLogService;

    @MockBean
    private ApiKeyService apiKeyService;
    @MockBean
    private RateLimitConfig rateLimitConfig;

    @Test
    void getDashboard_returnsOk() throws Exception {
        DashboardResponse dashboard = new DashboardResponse(
                5L, 20L, 3L, 15L, 5L, 1500.0, 0.75, 0.42, 5000L,
                3000L, 2000L,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                new DashboardResponse.TriggerDistribution(15, 3, 0, 0, 2)
        );
        when(learningService.getDashboard(30, null)).thenReturn(dashboard);

        mockMvc.perform(get("/api/v1/learning/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(5))
                .andExpect(jsonPath("$.totalRuns").value(20))
                .andExpect(jsonPath("$.totalInputTokens").value(3000))
                .andExpect(jsonPath("$.triggerDistribution.manual").value(15));
    }

    @Test
    void getDashboard_withParams_passesToService() throws Exception {
        DashboardResponse dashboard = new DashboardResponse(
                5L, 10L, 1L, 8L, 2L, 1000.0, 0.8, 0.1, 1000L,
                500L, 500L,
                List.of(), List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of(),
                new DashboardResponse.TriggerDistribution(10, 0, 0, 0, 0)
        );
        when(learningService.getDashboard(7, "proj-1")).thenReturn(dashboard);

        mockMvc.perform(get("/api/v1/learning/dashboard")
                        .param("days", "7")
                        .param("projectId", "proj-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(10));

        verify(learningService).getDashboard(7, "proj-1");
    }

    @Test
    void getPlatformStats_returnsOk() throws Exception {
        PlatformStatsResponse stats = new PlatformStatsResponse(
                3, 1, 10, 35, 16, 10, 6, List.of(),
                53, 50, 3, 5, 8, 3, 4,
                new PlatformStatsResponse.ChainStats(2, 5, 4, 1),
                2, 1, 8,
                new PlatformStatsResponse.TodoStats(5, 2, 10, 1, 8, 5, 5),
                new PlatformStatsResponse.WishStats(3, 2, 1, 0),
                new PlatformStatsResponse.AssistantStats(15, 4.5)
        );
        when(platformStatsService.getPlatformStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/learning/platform-stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeProjects").value(3))
                .andExpect(jsonPath("$.totalSkills").value(16))
                .andExpect(jsonPath("$.chainStats.totalChains").value(2))
                .andExpect(jsonPath("$.assistantStats.totalSessions").value(15));
    }

    @Test
    void getWorkflowStats_returnsOk() throws Exception {
        Map<String, Object> stats = Map.of(
                "totalRuns", 10,
                "successRate", 0.8
        );
        when(learningService.getWorkflowStats("wf-1")).thenReturn(stats);

        mockMvc.perform(get("/api/v1/learning/workflows/wf-1/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRuns").value(10))
                .andExpect(jsonPath("$.successRate").value(0.8));
    }

    @Test
    void getStepAnalytics_returnsOk() throws Exception {
        List<Map<String, Object>> analytics = List.of(
                Map.of("stepIndex", 0, "failureRate", 0.1)
        );
        when(learningService.getStepAnalytics("wf-1")).thenReturn(analytics);

        mockMvc.perform(get("/api/v1/learning/workflows/wf-1/step-analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].stepIndex").value(0));
    }

    @Test
    void getOptimizationSuggestions_returnsOk() throws Exception {
        List<Map<String, Object>> suggestions = List.of(
                Map.of("type", "LOW_SUCCESS_RATE", "severity", "HIGH")
        );
        when(learningService.getOptimizationSuggestions("wf-1")).thenReturn(suggestions);

        mockMvc.perform(get("/api/v1/learning/workflows/wf-1/suggestions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("LOW_SUCCESS_RATE"));
    }

    @Test
    void getSimilarWorkflows_returnsOk() throws Exception {
        List<Map<String, Object>> similar = List.of(
                Map.of("workflowId", "wf-2", "similarity", "80%")
        );
        when(learningService.getSimilarWorkflows("wf-1")).thenReturn(similar);

        mockMvc.perform(get("/api/v1/learning/workflows/wf-1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].workflowId").value("wf-2"));
    }

    @Test
    void rateRun_validRating_returnsOk() throws Exception {
        doNothing().when(learningService).rateRun("run-1", 4);

        mockMvc.perform(post("/api/v1/learning/runs/run-1/rate")
                        .param("rating", "4"))
                .andExpect(status().isOk());

        verify(learningService).rateRun("run-1", 4);
    }

    @Test
    void rateRun_nonExistentRun_returns404() throws Exception {
        doThrow(new ResourceNotFoundException("WorkflowRun", "unknown"))
                .when(learningService).rateRun("unknown", 3);

        mockMvc.perform(post("/api/v1/learning/runs/unknown/rate")
                        .param("rating", "3"))
                .andExpect(status().isNotFound());
    }
}
