package com.automation.dto.projection;

/** Zugeordnete (ohne ARCHIVED) und erledigte TODOs eines Meilensteins aus einem GROUP BY (TODO 259f4f66). */
public record MilestoneTodoCounts(String milestoneId, long total, long done) {}
