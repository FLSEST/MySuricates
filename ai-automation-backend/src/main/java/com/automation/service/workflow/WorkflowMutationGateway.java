package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.entity.ActivityLog.LogCategory;
import com.automation.entity.ActivityLog.LogLevel;
import com.automation.entity.ChangeSource;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorCode;
import com.automation.exception.HardeningException;
import com.automation.service.activitylog.ActivityLogService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The one place that records changes to a workflow's executable definition.
 *
 * <p>Every writer goes through {@link WorkflowService}, which calls {@link #record} after saving.
 * A version row describes the state <em>after</em> a change, with a server-determined
 * {@link ChangeSource} and the definition hash. If the state before the change is not described by
 * the newest row (legacy data, a bypass writer, a raw DB edit), that state is written first as
 * {@link ChangeSource#UNTRACKED} — drift becomes visible instead of silently lost.
 * {@code WorkflowWriterAllowlistTest} pins the few writers that bypass this class.
 *
 * <p>Being that one place, it is also where a definition is refused: a sealed workflow, and block
 * types no executor can run ({@link BlockTypeValidator}).
 */
@Component
@RequiredArgsConstructor
public class WorkflowMutationGateway {

    private final WorkflowVersionService versions;
    private final WorkflowDefinitionHasher hasher;
    private final ActivityLogService activityLog;
    private final BlockTypeValidator blockTypes;

    /**
     * @param saved  workflow after the change (already saved, same transaction)
     * @param before snapshot taken before the change; {@code null} for a newly created workflow
     */
    public void record(Workflow saved, WorkflowSnapshot before, ChangeSource source, String message) {
        WorkflowSnapshot after = WorkflowSnapshot.from(saved);
        List<String> changedFields = before == null ? List.of() : before.changedFields(after);
        if (before != null && changedFields.isEmpty()) {
            return; // labels only (name, tags, …) — not a new definition
        }
        if (saved.isSealed()) {
            // Runs inside the caller's transaction: throwing rolls the change back. Holds even when the
            // sealing switch is off later — turning the switch off must not silently unseal anything.
            throw new HardeningException(ErrorCode.WORKFLOW_SEALED, "Workflow '" + saved.getName()
                    + "' is sealed — unseal it before changing " + String.join(", ", changedFields));
        }
        // Runs inside the caller's transaction like the seal check: an unknown type rolls the save
        // back, so no definition the executor cannot run is ever stored (TODO 89dd9e34).
        blockTypes.rejectUnknown(after.blocks());
        String afterHash = hasher.hash(after);
        String beforeHash = before == null ? null : hasher.hash(before);
        if (beforeHash != null && !describedByNewestVersion(saved.getId(), beforeHash)) {
            versions.saveVersion(saved.getId(), before, beforeHash, ChangeSource.UNTRACKED,
                    "State before this change had no version");
        }
        int versionNumber = versions.saveVersion(saved.getId(), after, afterHash, source, message);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("changeSource", source.key());
        metadata.put("versionNumber", versionNumber);
        metadata.put("newHash", afterHash);
        if (beforeHash != null) {
            metadata.put("previousHash", beforeHash);
            metadata.put("changedFields", changedFields);
        }
        activityLog.log(LogLevel.INFO, LogCategory.SYSTEM,
                "Workflow definition " + (before == null ? "created" : "changed") + " by " + source.key() + ": " + saved.getName(),
                "WorkflowMutationGateway", saved.getId(), metadata, saved.getId());
    }

    /** Guard for operations that remove or replace a workflow as a whole (delete). */
    public void requireUnsealed(Workflow workflow, String operation) {
        if (workflow.isSealed()) {
            throw new HardeningException(ErrorCode.WORKFLOW_SEALED,
                    "Workflow '" + workflow.getName() + "' is sealed — unseal it before " + operation);
        }
    }

    private boolean describedByNewestVersion(String workflowId, String hash) {
        return versions.findLatest(workflowId).map(v -> hash.equals(v.getDefinitionHash())).orElse(false);
    }
}
