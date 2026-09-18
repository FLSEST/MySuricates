package com.automation.dto.request;

/**
 * Which workflow the editor wants a dashboard-report proposal for.
 *
 * @param workflowId the workflow whose latest run is read
 */
public record DashboardReportAssistRequest(String workflowId) {
}
