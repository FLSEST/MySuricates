package com.automation.dto.response;

import java.util.List;

/**
 * Die Runs eines Zeitplans: die neuesten Workflow- und Chain-Runs, je hoechstens so viele wie das
 * Limit der Anfrage, und wie viele es insgesamt gibt (TODO 86777fb4). Ein Zeitplan alle fuenf Minuten
 * sammelt in 90 Tagen ~26 000 Runs; vorher kamen sie alle. {@code workflowRuns} sind Listenzeilen ohne
 * Schritte (TODO 168e3d7f).
 *
 * @param workflowRunsTotal alle Workflow-Runs des Zeitplans; groesser als {@code workflowRuns.size()},
 *                          wenn das Limit gegriffen hat
 * @param chainRunsTotal    dasselbe fuer die Chain-Runs
 */
public record ScheduleRunsResponse(
        List<WorkflowRunListItemResponse> workflowRuns,
        List<ChainRunResponse> chainRuns,
        long workflowRunsTotal,
        long chainRunsTotal
) {}
