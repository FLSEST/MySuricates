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

    /**
     * Returns, per requested workflow, its latest run and its validated dashboard result, never step output.
     * {@code workflowIds} (repeated or comma-separated) takes at most 50 ids of the form
     * {@code ^[a-z0-9][a-z0-9-]{0,79}$}, otherwise 400; without ids the list is empty. One entry per distinct id in
     * request order; an unknown or soft-deleted workflow has {@code available: false} and {@code resultState: MISSING},
     * and {@code result} is only set when {@code resultState} is {@code VALID}.
     */
    @GetMapping
    public List<DashboardWorkflowResultResponse> results(
            @RequestParam(name = "workflowIds", required = false) List<String> workflowIds) {
        return service.results(workflowIds == null ? List.of() : workflowIds);
    }
}
