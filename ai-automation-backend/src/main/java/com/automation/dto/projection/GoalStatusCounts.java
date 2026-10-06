package com.automation.dto.projection;

/**
 * Status counters per goal from a single GROUP BY t.goalId (TODO dc0566f8) – loads no
 * TEXT columns. {@code openQuestions} counts only active TODOs (OPEN/IN_PROGRESS/BACKLOG) with an
 * open question.
 */
public record GoalStatusCounts(
        String goalId,
        long open,
        long inProgress,
        long done,
        long backlog,
        long archived,
        long openQuestions,
        /** Non-archived TODOs with a decision and without an open question (TODO 68a5e24e). */
        long decided
) {
    public GoalStatusCounts(String goalId, long open, long inProgress, long done, long backlog, long archived,
                            long openQuestions) {
        this(goalId, open, inProgress, done, backlog, archived, openQuestions, 0);
    }
}
