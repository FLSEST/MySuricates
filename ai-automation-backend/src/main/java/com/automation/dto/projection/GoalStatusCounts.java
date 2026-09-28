package com.automation.dto.projection;

/**
 * Status-Zaehler je Ziel aus einem einzigen GROUP BY t.goalId (TODO dc0566f8) – laedt keine
 * TEXT-Spalten. {@code openQuestions} zaehlt nur aktive TODOs (OPEN/IN_PROGRESS/BACKLOG) mit
 * offener Frage.
 */
public record GoalStatusCounts(
        String goalId,
        long open,
        long inProgress,
        long done,
        long backlog,
        long archived,
        long openQuestions,
        /** Nicht archivierte TODOs mit Entscheidung und ohne offene Frage (TODO 68a5e24e). */
        long decided
) {
    public GoalStatusCounts(String goalId, long open, long inProgress, long done, long backlog, long archived,
                            long openQuestions) {
        this(goalId, open, inProgress, done, backlog, archived, openQuestions, 0);
    }
}
