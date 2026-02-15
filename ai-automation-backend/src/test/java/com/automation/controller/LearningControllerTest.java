package com.automation.controller;

import com.automation.exception.GlobalExceptionHandler;
import com.automation.exception.ResourceNotFoundException;
import com.automation.service.learning.LearningService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(LearningController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class LearningControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private LearningService learningService;

    @Test
    void getDashboard_returnsOk() throws Exception {
        Map<String, Object> dashboard = Map.of(
                "totalWorkflows", 5L,
                "totalRuns", 20L,
                "completedRuns", 15L
        );
        when(learningService.getDashboard()).thenReturn(dashboard);

        mockMvc.perform(get("/api/v1/learning/dashboard"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalWorkflows").value(5))
                .andExpect(jsonPath("$.totalRuns").value(20));
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
