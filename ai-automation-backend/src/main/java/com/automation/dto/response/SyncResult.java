package com.automation.dto.response;

import java.util.List;

public record SyncResult(
        int projectsSynced,
        int workflowsSynced,
        int skillsSynced,
        int skipped,
        List<String> errors,
        String preSyncBackup
) {
    public SyncResult(int projectsSynced, int workflowsSynced, int skillsSynced, int skipped, List<String> errors) {
        this(projectsSynced, workflowsSynced, skillsSynced, skipped, errors, null);
    }
}
