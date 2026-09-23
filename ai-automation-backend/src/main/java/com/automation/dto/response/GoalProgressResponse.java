package com.automation.dto.response;

/**
 * Fortschritt eines Ziels. {@code total} zaehlt ohne ARCHIVED (Dubletten, Verworfenes);
 * {@code percentDone} ist {@code done / total}, gerundet.
 */
public record GoalProgressResponse(
        long total,
        long open,
        long inProgress,
        long backlog,
        long done,
        long openQuestions,
        int percentDone
) {
    public static GoalProgressResponse of(long open, long inProgress, long backlog, long done, long openQuestions) {
        long total = open + inProgress + backlog + done;
        int percent = total == 0 ? 0 : (int) Math.round(done * 100.0 / total);
        return new GoalProgressResponse(total, open, inProgress, backlog, done, openQuestions, percent);
    }
}
