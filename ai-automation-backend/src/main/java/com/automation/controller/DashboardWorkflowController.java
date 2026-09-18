package com.automation.controller;

import com.automation.dto.response.DashboardWorkflowResultResponse;
import com.automation.service.dashboard.DashboardWorkflowService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Data of the dashboard's workflow widgets (spec E3). Core feature — no module gate, listed in
 * ControllerModuleGateTest#CORE_CONTROLLERS.
 *
 * <p>Answers with the latest run of each requested workflow and the strictly validated
 * {@code dashboard_widget} contract — never step output, never unvalidated text. One request per
 * dashboard view, refreshed by the run-completed broadcast rather than by polling per widget.
 */
@RestController
@RequestMapping("/api/v1/dashboard/workflow-results")
@RequiredArgsConstructor
public class DashboardWorkflowController {

    private final DashboardWorkflowService service;

    /** Ids outside the allowed shape or more than DashboardWorkflowService.MAX_WORKFLOWS of them → 400. */
    @GetMapping
    public List<DashboardWorkflowResultResponse> results(
            @RequestParam(name = "workflowIds", required = false) List<String> workflowIds) {
        return service.results(workflowIds == null ? List.of() : workflowIds);
    }
}
