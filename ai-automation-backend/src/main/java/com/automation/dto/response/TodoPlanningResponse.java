package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;

/** Ziel und Einschaetzung eines TODOs (TODO dc0566f8); {@code goalTitle} fuer die Anzeige. */
public record TodoPlanningResponse(
        String goalId,
        String goalTitle,
        Integer goalRank,
        Todo.Complexity complexity,
        String suggestedEffort,
        String ratingNote,
        Todo.RatedBy ratedBy,
        Instant ratedAt,
        String openQuestion,
        /** Meilenstein im Ziel (TODO 259f4f66); null = keiner. */
        String milestoneId
) {}
