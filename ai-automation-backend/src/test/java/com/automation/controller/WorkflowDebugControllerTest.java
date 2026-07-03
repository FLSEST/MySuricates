package com.automation.controller;

import com.automation.config.RateLimitConfig;
import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.entity.RunStatus;
import com.automation.exception.GlobalExceptionHandler;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.execution.WorkflowDebugService;
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

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Auth test omitted: @AutoConfigureMockMvc(addFilters = false) disables security filters,
// matching the pattern in ActivityLogControllerTest — requests succeed without an API key.
@WebMvcTest(WorkflowDebugController.class)
@Import(GlobalExceptionHandler.class)
@AutoConfigureMockMvc(addFilters = false)
class WorkflowDebugControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockitoBean private WorkflowDebugService workflowDebugService;
    @MockitoBean private ApiKeyService apiKeyService;
    @MockitoBean private ActivityLogService activityLogService;
    @MockitoBean private RateLimitConfig rateLimitConfig;

    @Test
    void should_return200WithBrokenWorkflows_when_serviceReturnsEntries() throws Exception {
        var now = Instant.now();
        var entry1 = new BrokenWorkflowResponse(
                "wf-1", "My Workflow", "My Project", "run-1",
                RunStatus.FAILED, "Step timed out", 2, "shell",
                now.minusSeconds(120), now, 120_000L, "sched-1"
        );
        var entry2 = new BrokenWorkflowResponse(
                "wf-2", "Other Workflow", "Other Project", "run-2",
                RunStatus.COMPLETED_WITH_ERRORS, "Non-critical step failed", 0, "simple",
                now.minusSeconds(60), now, 60_000L, null
        );
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(entry1, entry2));

        mockMvc.perform(get("/api/v1/debug/failed-workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].workflowId").value("wf-1"))
                .andExpect(jsonPath("$[0].status").value("FAILED"))
                .andExpect(jsonPath("$[0].errorMessage").value("Step timed out"))
                .andExpect(jsonPath("$[0].failedStepIndex").value(2))
                .andExpect(jsonPath("$[0].failedBlockType").value("shell"))
                .andExpect(jsonPath("$[1].workflowId").value("wf-2"))
                .andExpect(jsonPath("$[1].status").value("COMPLETED_WITH_ERRORS"));
    }

    @Test
    void should_returnEmptyArray_when_noBrokenWorkflows() throws Exception {
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/debug/failed-workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void should_return200_when_entryHasNullOptionalFields() throws Exception {
        var entry = new BrokenWorkflowResponse(
                "wf-3", "Minimal Workflow", null, "run-3",
                RunStatus.FAILED, null, null, null,
                Instant.now(), null, null, null
        );
        when(workflowDebugService.getBrokenWorkflows()).thenReturn(List.of(entry));

        mockMvc.perform(get("/api/v1/debug/failed-workflows"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].workflowId").value("wf-3"));
    }
}
