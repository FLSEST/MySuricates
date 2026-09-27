package com.automation.dto.projection;

/**
 * Zugeordnete (ohne ARCHIVED) und erledigte TODOs eines Meilensteins aus einem GROUP BY
 * (TODO 259f4f66); {@code openQuestions} zaehlt aktive TODOs mit offener Frage (TODO c60bf914).
 */
public record MilestoneTodoCounts(String milestoneId, long total, long done, long openQuestions) {

    public MilestoneTodoCounts(String milestoneId, long total, long done) {
        this(milestoneId, total, done, 0);
    }
}
