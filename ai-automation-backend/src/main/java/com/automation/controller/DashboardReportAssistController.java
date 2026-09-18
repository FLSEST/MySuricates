package com.automation.controller;

import com.automation.dto.request.DashboardReportAssistRequest;
import com.automation.dto.response.DashboardReportAssistResponse;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.dashboard.DashboardReportAssistService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The editor's AI proposal for a {@code dashboard-report} block (TODO f4231dc9). Belongs to the
 * {@code dashboard-report} module, so a disabled module answers 404 MODULE_DISABLED — the block
 * form stays usable by hand either way.
 *
 * <p>POST, not GET: the call costs tokens and must never be something a browser prefetches.
 */
@RestController
@RequestMapping("/api/v1/dashboard/report-assist")
@RequiredArgsConstructor
@RequiresModule(ModuleCatalog.DASHBOARD_REPORT)
public class DashboardReportAssistController {

    private final DashboardReportAssistService service;

    /**
     * 400 for a missing id, 404 for an unknown workflow, 409 when the setting is off, the provider
     * is missing, there is no usable run, or the answer did not match the contract.
     */
    @PostMapping
    public DashboardReportAssistResponse propose(@RequestBody DashboardReportAssistRequest request) {
        return service.propose(request == null ? null : request.workflowId());
    }
}
