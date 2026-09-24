package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Ziel-Waechter ein- oder ausschalten ({@code POST /goals/{id}/guard}, TODO 259f4f66).
 *
 * @param projectId nur beim ersten Einschalten: Projekt des Waechter-Workflows; fehlt er, das
 *                  Projekt mit den meisten TODOs des Ziels, sonst das erste aktive Projekt
 * @param cron      Spring-CRON mit Sekunden ({@code 0 0 8 * * *} = taeglich 08:00, Vorgabe);
 *                  beim Wiedereinschalten ersetzt ein gesetzter Wert den bisherigen
 * @param locale    {@code de}/{@code en}: Sprache von Workflow und Kachel, nur beim ersten Einschalten
 */
public record GoalGuardRequest(
        @NotNull Boolean enabled,
        String projectId,
        String cron,
        String locale
) {}
