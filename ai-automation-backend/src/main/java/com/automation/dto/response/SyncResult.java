package com.automation.dto.response;

import java.util.List;

/**
 * Outcome of a definition sync. {@code notes}: what was left behind on purpose — for a sealed-only
 * target (Prod) references to unsealed workflows that did not go along (TODO 84a78bb7).
 */
public record SyncResult(
        int projectsSynced,
        int workflowsSynced,
        int skillsSynced,
        int chainsSynced,
        int skipped,
        List<String> errors,
        String preSyncBackup,
        List<String> notes
) {
    public SyncResult(int projectsSynced, int workflowsSynced, int skillsSynced, int chainsSynced, int skipped,
                      List<String> errors, String preSyncBackup) {
        this(projectsSynced, workflowsSynced, skillsSynced, chainsSynced, skipped, errors, preSyncBackup, List.of());
    }

    public SyncResult(int projectsSynced, int workflowsSynced, int skillsSynced, int skipped, List<String> errors) {
        this(projectsSynced, workflowsSynced, skillsSynced, 0, skipped, errors, null);
    }

    public SyncResult(int projectsSynced, int workflowsSynced, int skillsSynced, int skipped, List<String> errors, String preSyncBackup) {
        this(projectsSynced, workflowsSynced, skillsSynced, 0, skipped, errors, preSyncBackup);
    }

    public SyncResult withNotes(List<String> notes) {
        return new SyncResult(projectsSynced, workflowsSynced, skillsSynced, chainsSynced, skipped, errors, preSyncBackup, notes);
    }
}
