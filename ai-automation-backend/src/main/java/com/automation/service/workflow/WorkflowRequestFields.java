package com.automation.service.workflow;

import com.automation.dto.WorkflowSnapshot;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;
import com.automation.executor.ExecutionMode;
import com.automation.service.ai.ReasoningEffort;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Carries the fields of a create, update or restore onto the workflow entity and checks them on the
 * way: reserved block names, unknown execution modes, the effort level, the version label (TODO
 * 5c419baa). Pure — no repository, no event, no version row: loading, saving, recording the change
 * and publishing stay with {@link WorkflowService}, which had grown to 284 lines around this.
 *
 * <p>A check that fails throws {@link IllegalArgumentException} (400) before the service saves
 * anything; fields applied before it roll back with the service's transaction.
 */
final class WorkflowRequestFields {

    static final int MAX_VERSION_LENGTH = 255;

    private WorkflowRequestFields() {
    }

    /**
     * A sub-workflow that still names a blueprint ({@code blueprint-…}) would fail at run time: only the
     * template installation creates the called workflow and fills in its id (TODO 093d2f11).
     */
    static void rejectUnwiredBlueprints(List<BlockDefinition> blocks) {
        if (blocks == null) {
            return;
        }
        for (BlockDefinition block : blocks) {
            if (block != null && "sub-workflow".equals(block.getType()) && block.getConfig() != null
                    && block.getConfig().get("workflow_id") instanceof String id
                    && id.startsWith(TemplatePackageInstaller.BLUEPRINT_PREFIX)) {
                throw new LocalizedArgumentException(ErrorMessage.UNWIRED_BLUEPRINT_REFERENCE, Map.of(
                        "block", block.getName() != null ? block.getName() : block.getType(), "blueprint", id));
            }
        }
    }

    /** A new workflow of {@code project} from the request — without skills, which need the repository. */
    static Workflow newWorkflow(Project project, CreateWorkflowRequest request) {
        BlockNameRules.rejectReservedNames(request.blocks());
        ExecutionMode.rejectUnknown(request.blocks());
        rejectUnwiredBlueprints(request.blocks());

        Workflow workflow = Workflow.builder()
                .project(project)
                .name(request.name())
                .description(request.description())
                .icon(request.icon())
                .blocks(request.blocks() != null ? request.blocks() : new ArrayList<>())
                .instructions(request.instructions())
                .model(request.model())
                .provider(request.provider())
                .effort(normalizeEffort(request.effort()))
                .templateId(request.templateId())
                .build();

        if (request.version() != null && !request.version().isBlank()) { // blank keeps the default 1.0.0
            workflow.setVersion(normalizeVersion(request.version()));
        }
        if (request.metadata() != null) {
            workflow.getMetadata().putAll(request.metadata());
        }
        if (request.defaultContext() != null) {
            workflow.getDefaultContext().putAll(request.defaultContext());
        }
        return workflow;
    }

    /**
     * Applies every field the request carries ({@code null} leaves it unchanged).
     *
     * @return whether the request touched name, description or blocks — the content that
     *         {@link WorkflowContentChangedEvent} announces
     */
    static boolean apply(Workflow workflow, UpdateWorkflowRequest request) {
        if (request.name() != null) {
            workflow.setName(request.name());
        }
        if (request.description() != null) {
            workflow.setDescription(request.description());
        }
        if (request.icon() != null) {
            workflow.setIcon(request.icon());
        }
        if (request.blocks() != null) {
            BlockNameRules.rejectReservedNames(request.blocks());
            ExecutionMode.rejectUnknown(request.blocks());
            rejectUnwiredBlueprints(request.blocks());
            workflow.setBlocks(request.blocks());
        }
        if (request.instructions() != null) {
            workflow.setInstructions(request.instructions());
        }
        if (request.model() != null) {
            workflow.setModel(request.model());
        }
        if (request.provider() != null) {
            workflow.setProvider(request.provider());
        }
        if (request.effort() != null) {
            workflow.setEffort(normalizeEffort(request.effort()));
        }
        if (request.metadata() != null) {
            Map<String, Object> merged = new HashMap<>(workflow.getMetadata());
            merged.putAll(request.metadata());
            workflow.setMetadata(merged);
        }
        if (request.defaultContext() != null) {
            workflow.setDefaultContext(new HashMap<>(request.defaultContext()));
        }
        if (request.tags() != null) {
            // User-provided tags lock the workflow from auto-regeneration.
            workflow.setTags(new ArrayList<>(request.tags()));
            workflow.setTagsUserEdited(true);
            workflow.setTagsGeneratedAt(Instant.now());
        }
        if (request.category() != null) {
            workflow.setCategory(request.category());
            workflow.setTagsUserEdited(true);
        }
        if (request.version() != null) {
            // A label like the name: not in WorkflowSnapshot, so the gateway writes no version row for it.
            workflow.setVersion(normalizeVersion(request.version()));
        }
        return request.name() != null || request.description() != null || request.blocks() != null;
    }

    /** Every versioned field of the snapshot, {@code null} included — a restore replaces, it does not merge. */
    static void restore(Workflow workflow, WorkflowSnapshot snapshot) {
        workflow.setBlocks(snapshot.blocks() != null ? snapshot.blocks() : new ArrayList<>());
        workflow.setInstructions(snapshot.instructions());
        workflow.setModel(snapshot.model());
        workflow.setProvider(snapshot.provider());
        workflow.setEffort(snapshot.effort());
        workflow.setMetadata(snapshot.metadata() != null ? new HashMap<>(snapshot.metadata()) : new HashMap<>());
        workflow.setDefaultContext(snapshot.defaultContext() != null ? new HashMap<>(snapshot.defaultContext()) : new HashMap<>());
    }

    /**
     * The version label, trimmed. Blank is rejected (400) — {@code null} in the update request is the
     * way to leave it unchanged; create checks for blank itself and keeps the default. The column is
     * TEXT without a limit (V5); 255 is the entity's mapped length.
     */
    static String normalizeVersion(String version) {
        String trimmed = version.strip();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("Invalid version: must not be blank (omit it to keep the current one)");
        }
        if (trimmed.length() > MAX_VERSION_LENGTH) {
            throw new IllegalArgumentException("Invalid version: at most " + MAX_VERSION_LENGTH + " characters");
        }
        return trimmed;
    }

    /** Blank clears the level (null); invalid values are rejected with 400. */
    static String normalizeEffort(String effort) {
        if (effort == null || effort.isBlank()) {
            return null;
        }
        return ReasoningEffort.parse(effort)
                .map(ReasoningEffort::cliValue)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Invalid effort level: '" + effort + "' (valid: low, medium, high, xhigh)"));
    }
}
