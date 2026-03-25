package com.automation.dto.response;

public record SyncPreviewItem(
        String id,
        String name,
        String projectName,
        String action,
        WorkflowDiff diff
) {
    public SyncPreviewItem(String id, String name, String projectName, String action) {
        this(id, name, projectName, action, null);
    }
}
