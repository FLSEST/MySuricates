package com.automation.controller;

import com.automation.dto.response.WorkflowResponse;
import com.automation.mapper.WorkflowMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.workflow.WorkflowSealService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seal / unseal a workflow. Only available with {@code automation.hardening.sealing=true}; a seal
 * that exists stays effective even if the switch is turned off later.
 */
@RequiresModule(ModuleCatalog.SEALING)
@RestController
@RequestMapping("/api/v1/workflows/{id}")
@RequiredArgsConstructor
public class WorkflowSealController {

    private final WorkflowSealService sealService;
    private final WorkflowMapper workflowMapper;

    @PostMapping("/seal")
    public WorkflowResponse seal(@PathVariable String id) {
        return workflowMapper.toResponse(sealService.seal(id));
    }

    @PostMapping("/unseal")
    public WorkflowResponse unseal(@PathVariable String id) {
        return workflowMapper.toResponse(sealService.unseal(id));
    }
}
