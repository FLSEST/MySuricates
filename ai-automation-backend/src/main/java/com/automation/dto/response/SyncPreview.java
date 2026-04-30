package com.automation.dto.response;

import java.util.List;

public record SyncPreview(
        List<SyncPreviewItem> projects,
        List<SyncPreviewItem> workflows,
        List<SyncPreviewItem> skills,
        List<SyncPreviewItem> chains
) {
    public SyncPreview(List<SyncPreviewItem> projects, List<SyncPreviewItem> workflows, List<SyncPreviewItem> skills) {
        this(projects, workflows, skills, List.of());
    }
}
