package com.automation.dto.response;

import java.util.List;

/**
 * Dry-run of a definition sync. {@code notes}: what stays behind and why — for a sealed-only target
 * (Prod) the references of sealed workflows to unsealed ones and the chains left out (TODO 84a78bb7).
 */
public record SyncPreview(
        List<SyncPreviewItem> projects,
        List<SyncPreviewItem> workflows,
        List<SyncPreviewItem> skills,
        List<SyncPreviewItem> chains,
        List<String> notes
) {
    public SyncPreview(List<SyncPreviewItem> projects, List<SyncPreviewItem> workflows, List<SyncPreviewItem> skills,
                       List<SyncPreviewItem> chains) {
        this(projects, workflows, skills, chains, List.of());
    }

    public SyncPreview(List<SyncPreviewItem> projects, List<SyncPreviewItem> workflows, List<SyncPreviewItem> skills) {
        this(projects, workflows, skills, List.of());
    }
}
