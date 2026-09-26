package com.automation.dto.response;

import java.util.List;

/**
 * Der Kalender eines Zeitraums: die anstehenden Termine und die vergangenen Runs.
 *
 * <p>{@code pastRuns} sind Listenzeilen ohne Schritte. Mit {@link WorkflowRunResponse} trug der
 * Kalender jeden Schritt-Output aller Runs des Zeitraums mit: 3 697 137 Bytes fuer eine Woche mit
 * 60 Runs im RequestCostRatchetTest, obwohl der Kalender nur Name, Status und Startzeit zeigt
 * (TODO 168e3d7f).
 *
 * <p>{@code pastRuns} sind die neuesten Runs des Zeitraums bis zum Limit der Anfrage,
 * {@code pastRunsTotal} alle (TODO 86777fb4): Ein Monat mit einem Zeitplan alle fuenf Minuten
 * sind 8 640 Runs.
 */
public record CalendarDataResponse(
        List<ScheduledWorkflowResponse> upcoming,
        List<WorkflowRunListItemResponse> pastRuns,
        long pastRunsTotal
) {
    /** Alle Runs des Zeitraums in {@code pastRuns}, keiner abgeschnitten. */
    public CalendarDataResponse(List<ScheduledWorkflowResponse> upcoming, List<WorkflowRunListItemResponse> pastRuns) {
        this(upcoming, pastRuns, pastRuns == null ? 0 : pastRuns.size());
    }
}
