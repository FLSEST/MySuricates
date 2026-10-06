package com.automation.dto.projection;

/**
 * Assigned (excluding ARCHIVED) and completed TODOs of a work package from one GROUP BY
 * (TODO 259f4f66, c60bf914); {@code openQuestions} counts active TODOs with an open question, {@code decided}
 * those with a decision and without an open question (TODO 68a5e24e).
 */
public record WorkPackageTodoCounts(String workPackageId, long total, long done, long openQuestions, long decided) {

    public WorkPackageTodoCounts(String workPackageId, long total, long done) {
        this(workPackageId, total, done, 0, 0);
    }
}
