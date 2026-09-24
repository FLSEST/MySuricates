package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.entity.Workflow;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.execution.ExecutionQueryService;
import com.automation.service.learning.WorkflowSimilarityService;
import com.automation.service.repair.AutoRepairService;
import com.automation.service.system.ApiKeyService;
import com.automation.service.workflow.WorkflowOptimizationService;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WorkflowAssistController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowAssistControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WorkflowService workflowService;
    @MockitoBean
    private ExecutionQueryService executionQueryService;
    @MockitoBean
    private WorkflowOptimizationService optimizationService;
    @MockitoBean
    private WorkflowSimilarityService workflowSimilarityService;
    @MockitoBean
    private AutoRepairService autoRepairService;
    @MockitoBean
    private ActivityLogService activityLogService;
    @MockitoBean
    private ApiKeyService apiKeyService;
    @MockitoBean
    private RateLimitConfig rateLimitConfig;

    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testWorkflow = Workflow.builder().id("wf-1").name("Test Workflow").build();
    }

    @Test
    void optimizeWorkflow_returnsSuggestion() throws Exception {
        when(optimizationService.optimizeWorkflow("wf-1")).thenReturn(Map.of("summary", "kürzer"));

        mockMvc.perform(post("/api/v1/workflows/wf-1/optimize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary").value("kürzer"));
    }

    @Test
    void repairWorkflow_sealed_returns409WithoutStartingTheRepair() throws Exception {
        testWorkflow.setSealed(true);
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);

        mockMvc.perform(post("/api/v1/workflows/wf-1/repair"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WORKFLOW_SEALED"))
                .andExpect(jsonPath("$.error").value(containsString("unseal it before repairing")));

        verifyNoInteractions(autoRepairService);
    }

    @Test
    void repairWorkflow_withoutFailedRuns_returns400() throws Exception {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(executionQueryService.hasFailedRuns("wf-1")).thenReturn(false);

        mockMvc.perform(post("/api/v1/workflows/wf-1/repair"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Workflow 'Test Workflow' has no failed runs"));

        verify(autoRepairService, never()).repairWorkflow(anyString());
    }

    @Test
    void repairWorkflow_withFailedRuns_startsRepairAndReturns202() throws Exception {
        when(workflowService.findById("wf-1")).thenReturn(testWorkflow);
        when(executionQueryService.hasFailedRuns("wf-1")).thenReturn(true);

        mockMvc.perform(post("/api/v1/workflows/wf-1/repair"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("repair_started"))
                .andExpect(jsonPath("$.workflowId").value("wf-1"))
                .andExpect(jsonPath("$.workflowName").value("Test Workflow"));

        verify(autoRepairService).repairWorkflow("wf-1");
    }

    @Test
    void getSimilarWorkflows_returnsCappedList() throws Exception {
        List<Map<String, Object>> matches = List.of(
                Map.of("workflowId", "wf-2", "workflowName", "Sibling A", "similarity", 90L, "blockTypes", List.of("simple")),
                Map.of("workflowId", "wf-3", "workflowName", "Sibling B", "similarity", 70L, "blockTypes", List.of("simple")),
                Map.of("workflowId", "wf-4", "workflowName", "Sibling C", "similarity", 60L, "blockTypes", List.of("simple"))
        );
        when(workflowSimilarityService.getSimilarWorkflows("wf-1")).thenReturn(matches);

        mockMvc.perform(get("/api/v1/workflows/wf-1/similar?limit=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].workflowId").value("wf-2"))
                .andExpect(jsonPath("$[1].workflowId").value("wf-3"));
    }

    @Test
    void getSimilarWorkflows_defaultLimit_returnsOk() throws Exception {
        when(workflowSimilarityService.getSimilarWorkflows("wf-1")).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/workflows/wf-1/similar"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$", hasSize(0)));
    }
}
