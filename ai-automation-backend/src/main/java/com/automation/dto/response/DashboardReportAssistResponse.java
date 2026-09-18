package com.automation.dto.response;

import java.time.Instant;

/**
 * A proposal for a {@code dashboard-report} block's fields — already validated against the closed
 * contract in the backend. The raw AI answer and the run's output never leave the server.
 *
 * @param runId          the run the proposal was derived from, so the editor can say which one
 * @param runCompletedAt when that run ended; null for a run that has no end timestamp
 * @param provider       which AI provider produced it
 * @param proposal       the validated contract, identical in shape to a dashboard result
 */
public record DashboardReportAssistResponse(
        String runId,
        Instant runCompletedAt,
        String provider,
        DashboardWorkflowResultResponse.Result proposal) {
}
