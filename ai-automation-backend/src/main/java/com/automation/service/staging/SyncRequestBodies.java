package com.automation.service.staging;

import com.automation.entity.ChainEntry;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.service.workflow.DefinitionIntegrity;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The REST bodies {@link InstanceSyncHelper} sends to a Stable/Test instance — what of a local definition
 * travels, in the target's request format. Split out of the helper, which keeps the order of the sync
 * and the id mapping between the instances.
 */
final class SyncRequestBodies {

    private SyncRequestBodies() {
    }

    /** {@code POST /projects}. */
    static Map<String, Object> project(Project project) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", project.getName());
        body.put("type", project.getType().name());
        body.put("path", project.getPath());
        if (project.getSubtitle() != null) body.put("subtitle", project.getSubtitle());
        if (project.getDescription() != null) body.put("description", project.getDescription());
        if (project.getConfig() != null && !project.getConfig().isEmpty()) {
            body.put("config", project.getConfig());
        }
        return body;
    }

    /**
     * The definition fields PUT and import share. The version label travels along (TODO 62422aef); a target
     * that does not know a field yet ignores it.
     */
    static Map<String, Object> definition(Workflow workflow) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", workflow.getName());
        body.put("description", workflow.getDescription());
        body.put("icon", workflow.getIcon());
        body.put("blocks", workflow.getBlocks());
        if (workflow.getInstructions() != null) body.put("instructions", workflow.getInstructions());
        if (workflow.getModel() != null) body.put("model", workflow.getModel());
        if (workflow.getVersion() != null && !workflow.getVersion().isBlank()) body.put("version", workflow.getVersion());
        if (workflow.getMetadata() != null && !workflow.getMetadata().isEmpty()) body.put("metadata", workflow.getMetadata());
        return body;
    }

    /**
     * {@code POST /workflows/import} for a workflow new on the target: the definition, the target project,
     * the default context (an update leaves the target's own) and the integrity checksum over exactly these
     * fields, so the target verifies the transfer instead of logging "no integrity checksum" (TODO b18939a6).
     */
    static Map<String, Object> workflowImport(Workflow workflow, String targetProjectId) {
        Map<String, Object> body = definition(workflow);
        if (targetProjectId != null) body.put("projectId", targetProjectId);
        if (workflow.getDefaultContext() != null && !workflow.getDefaultContext().isEmpty()) {
            body.put("defaultContext", workflow.getDefaultContext());
        }
        body.put(DefinitionIntegrity.FIELD, DefinitionIntegrity.ofImportBody(body));
        return body;
    }

    /** {@code POST /skills/import}. */
    static Map<String, Object> skill(Skill skill) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", skill.getName());
        body.put("description", skill.getDescription());
        body.put("icon", skill.getIcon());
        body.put("category", skill.getCategory().name());
        body.put("aiContext", skill.getAiContext());
        if (skill.getConfigSchema() != null && !skill.getConfigSchema().isEmpty()) {
            body.put("configSchema", skill.getConfigSchema());
        }
        return body;
    }

    /** One chain entry, pointing at the workflow's id on the target. */
    static Map<String, Object> chainEntry(ChainEntry entry, String targetWorkflowId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("workflowId", targetWorkflowId);
        if (entry.getLabel() != null) body.put("label", entry.getLabel());
        body.put("sortOrder", entry.getSortOrder());
        body.put("stopOnFailure", entry.isStopOnFailure());
        if (entry.getInputContext() != null && !entry.getInputContext().isEmpty()) {
            body.put("inputContext", entry.getInputContext());
        }
        return body;
    }

    /** {@code POST/PUT /chains}. */
    static Map<String, Object> chain(WorkflowChain chain, List<Map<String, Object>> entries) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", chain.getName());
        if (chain.getDescription() != null) body.put("description", chain.getDescription());
        if (chain.getIcon() != null) body.put("icon", chain.getIcon());
        body.put("entries", entries);
        body.put("stopOnFailure", chain.isStopOnFailure());
        return body;
    }
}
