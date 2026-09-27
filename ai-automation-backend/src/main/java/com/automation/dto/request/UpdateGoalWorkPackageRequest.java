package com.automation.dto.request;

/**
 * Teilaenderung eines Arbeitspakets (TODO c60bf914): {@code null} laesst ein Feld stehen, {@code ""}
 * leert {@code startDate}/{@code endDate}. Das Ergebnis muss beide Daten haben oder keins, Start nie
 * nach dem Ende – sonst 400. {@code projectId} wechselt das Projekt, nur solange dem Paket keine TODOs
 * zugeordnet sind.
 */
public record UpdateGoalWorkPackageRequest(
        String title,
        String projectId,
        String startDate,
        String endDate
) {}
