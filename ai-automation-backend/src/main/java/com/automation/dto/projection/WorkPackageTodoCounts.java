package com.automation.dto.projection;

/**
 * Zugeordnete (ohne ARCHIVED) und erledigte TODOs eines Arbeitspakets aus einem GROUP BY
 * (TODO 259f4f66, c60bf914); {@code openQuestions} zaehlt aktive TODOs mit offener Frage.
 */
public record WorkPackageTodoCounts(String workPackageId, long total, long done, long openQuestions) {

    public WorkPackageTodoCounts(String workPackageId, long total, long done) {
        this(workPackageId, total, done, 0);
    }
}
