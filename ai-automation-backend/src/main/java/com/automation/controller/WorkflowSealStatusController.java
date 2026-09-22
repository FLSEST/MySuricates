package com.automation.controller;

import com.automation.dto.response.WorkflowSealStatusResponse;
import com.automation.service.workflow.WorkflowSealStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only seal status for the UI. Deliberately not behind the {@code sealing} module: a seal set
 * while the switch was on stays in force after it is turned off, so its state must stay visible.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowSealStatusController {

    private final WorkflowSealStatusService statusService;

    /** Every sealed workflow plus every workflow with refused starts in the last seven days. */
    @GetMapping("/seal-status")
    public List<WorkflowSealStatusResponse> sealStatus() {
        return statusService.statusAll();
    }
}
