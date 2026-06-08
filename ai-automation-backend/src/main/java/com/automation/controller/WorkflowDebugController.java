package com.automation.controller;

import com.automation.dto.response.BrokenWorkflowResponse;
import com.automation.service.execution.WorkflowDebugService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Workflow-Debug endpoints — back the /debug "what is currently broken" page.
 */
@RestController
@RequestMapping("/api/v1/debug")
@RequiredArgsConstructor
public class WorkflowDebugController {

    private final WorkflowDebugService workflowDebugService;

    /**
     * Workflows whose most recent run failed (FAILED / COMPLETED_WITH_ERRORS), latest first.
     * Restart a workflow by retrying its run via {@code POST /api/v1/executions/{runId}/retry}.
     */
    @GetMapping("/failed-workflows")
    public List<BrokenWorkflowResponse> failedWorkflows() {
        return workflowDebugService.getBrokenWorkflows();
    }
}
