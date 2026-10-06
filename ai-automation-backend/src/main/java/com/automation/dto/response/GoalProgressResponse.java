package com.automation.dto.response;

/**
 * Progress of a goal. {@code total} counts without ARCHIVED (duplicates, discarded);
 * {@code percentDone} is {@code done / total}, rounded. {@code decided} counts the non-
 * archived TODOs with a decision and without an open question (TODO 68a5e24e).
 */
public record GoalProgressResponse(
        long total,
        long open,
        long inProgress,
        long backlog,
        long done,
        long openQuestions,
        int percentDone,
        long decided
) {
    public static GoalProgressResponse of(long open, long inProgress, long backlog, long done, long openQuestions) {
        return of(open, inProgress, backlog, done, openQuestions, 0);
    }

    public static GoalProgressResponse of(long open, long inProgress, long backlog, long done, long openQuestions,
                                          long decided) {
        long total = open + inProgress + backlog + done;
        int percent = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        return new GoalProgressResponse(total, open, inProgress, backlog, done, openQuestions, percent, decided);
    }
}
