package com.automation.dto.response;

import java.util.List;

/**
 * Der Kalender eines Zeitraums: die anstehenden Termine und die vergangenen Runs.
 *
 * <p>{@code pastRuns} sind Listenzeilen ohne Schritte. Mit {@link WorkflowRunResponse} trug der
 * Kalender jeden Schritt-Output aller Runs des Zeitraums mit: 3 697 137 Bytes fuer eine Woche mit
 * 60 Runs im RequestCostRatchetTest, obwohl der Kalender nur Name, Status und Startzeit zeigt
 * (TODO 168e3d7f).
 */
public record CalendarDataResponse(
        List<ScheduledWorkflowResponse> upcoming,
        List<WorkflowRunListItemResponse> pastRuns
) {}
