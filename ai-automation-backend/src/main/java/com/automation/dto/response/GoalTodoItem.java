package com.automation.dto.response;

import com.automation.entity.Todo;

import java.time.Instant;
import java.util.List;

/** Ein TODO in der Zielansicht – ohne Beschreibung (TODO dc0566f8). */
public record GoalTodoItem(
        String id,
        String title,
        Todo.Status status,
        Todo.Priority priority,
        String projectId,
        String projectName,
        List<String> tags,
        Integer goalRank,
        Todo.Complexity complexity,
        String suggestedEffort,
        String ratingNote,
        Todo.RatedBy ratedBy,
        Instant ratedAt,
        String openQuestion,
        Instant updatedAt,
        /** Faelligkeit des TODOs (TODO 259f4f66); null = ungeplant. */
        Instant dueDate,
        /** Meilenstein des Ziels, dem das TODO zugeordnet ist; null = keiner. */
        String milestoneId
) {}
