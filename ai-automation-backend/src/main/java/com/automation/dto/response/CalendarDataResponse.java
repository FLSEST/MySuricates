package com.automation.dto.response;

import java.util.List;

public record CalendarDataResponse(
        List<ScheduledWorkflowResponse> upcoming,
        List<WorkflowRunResponse> pastRuns
) {}
