package com.automation.dto;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record WorkflowSnapshot(
        List<BlockDefinition> blocks,
        String instructions,
        String model,
        String provider,
        Map<String, Object> metadata,
        Map<String, Object> defaultContext,
        List<String> activatedSkillIds,
        String effort
) {

    /** Backwards-compat constructor for call-sites written before effort support existed. */
    public WorkflowSnapshot(List<BlockDefinition> blocks, String instructions, String model,
                            String provider, Map<String, Object> metadata,
                            Map<String, Object> defaultContext, List<String> activatedSkillIds) {
        this(blocks, instructions, model, provider, metadata, defaultContext, activatedSkillIds, null);
    }

    public static WorkflowSnapshot from(Workflow workflow) {
        return new WorkflowSnapshot(
                workflow.getBlocks() != null ? List.copyOf(workflow.getBlocks()) : List.of(),
                workflow.getInstructions(),
                workflow.getModel(),
                workflow.getProvider(),
                workflow.getMetadata() != null ? Map.copyOf(workflow.getMetadata()) : Map.of(),
                workflow.getDefaultContext() != null ? Map.copyOf(workflow.getDefaultContext()) : Map.of(),
                workflow.getActivatedSkills() != null
                        ? workflow.getActivatedSkills().stream().map(Skill::getId).toList()
                        : List.of(),
                workflow.getEffort()
        );
    }

    public boolean differs(WorkflowSnapshot other) {
        return !changedFields(other).isEmpty();
    }

    /** Names of the executable fields that differ between this snapshot and {@code other}. */
    public List<String> changedFields(WorkflowSnapshot other) {
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(this.blocks, other.blocks)) changed.add("blocks");
        if (!Objects.equals(this.instructions, other.instructions)) changed.add("instructions");
        if (!Objects.equals(this.model, other.model)) changed.add("model");
        if (!Objects.equals(this.provider, other.provider)) changed.add("provider");
        if (!Objects.equals(this.metadata, other.metadata)) changed.add("metadata");
        if (!Objects.equals(this.defaultContext, other.defaultContext)) changed.add("defaultContext");
        if (!Objects.equals(this.activatedSkillIds, other.activatedSkillIds)) changed.add("activatedSkillIds");
        if (!Objects.equals(this.effort, other.effort)) changed.add("effort");
        return changed;
    }
}
