package com.automation.controller;

import com.automation.dto.response.WorkflowSealEventResponse;
import com.automation.dto.response.WorkflowSealStatusResponse;
import com.automation.service.workflow.WorkflowSealHistory;
import com.automation.service.workflow.WorkflowSealStatusService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * Read-only seal status for the UI. Deliberately not behind the {@code sealing} module: a seal set
 * while the switch was on stays in force after it is turned off, so its state must stay visible.
 */
@RestController
@RequestMapping("/api/v1/workflows")
@RequiredArgsConstructor
public class WorkflowSealStatusController {

    private final WorkflowSealStatusService statusService;
    private final WorkflowSealHistory history;

    /** Sealing and unsealing of one workflow, newest first — kept beyond the activity log's retention. */
    @GetMapping("/{id}/seal-events")
    public List<WorkflowSealEventResponse> sealEvents(@PathVariable String id) {
        Set<String> broken = Set.copyOf(history.brokenChainEntries());
        return history.forWorkflow(id).stream()
                .map(event -> WorkflowSealEventResponse.from(event, !broken.contains(event.getId())))
                .toList();
    }

    /** Every sealed workflow plus every workflow with refused starts in the last seven days. */
    @GetMapping("/seal-status")
    public List<WorkflowSealStatusResponse> sealStatus() {
        return statusService.statusAll();
    }
}
