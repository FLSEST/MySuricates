package com.automation.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * Switch the goal guard on or off ({@code POST /goals/{id}/guard}, TODO 259f4f66).
 *
 * @param projectId only on first switch-on: project of the guard workflow; if missing, the
 *                  project with the most TODOs of the goal, else the first active project
 * @param cron      Spring CRON with seconds ({@code 0 0 8 * * *} = daily 08:00, default);
 *                  on switching on again a set value replaces the previous one
 * @param locale    {@code de}/{@code en}: language of workflow and tile, only on first switch-on
 */
public record GoalGuardRequest(
        @NotNull Boolean enabled,
        String projectId,
        String cron,
        String locale
) {}
