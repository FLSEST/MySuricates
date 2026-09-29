package com.automation.dto.bundle;

import java.util.List;

/** Answer of {@code POST /api/v1/projects/import} (201). */
public record BundleImportResult(String projectId, Created created, List<Skipped> skipped,
                                 List<Renamed> renamed, List<String> warnings) {

    public record Created(int workflows, int chains, int schedules) {}

    /** {@code kind}: {@code workflow}, {@code chain} or {@code schedule}. */
    public record Skipped(String kind, String key, String reason) {}

    public record Renamed(String kind, String key, String from, String to) {}
}
