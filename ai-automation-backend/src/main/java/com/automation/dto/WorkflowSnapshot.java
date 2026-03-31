package com.automation.dto;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;

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
        List<String> activatedSkillIds
) {

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
                        : List.of()
        );
    }

    public boolean differs(WorkflowSnapshot other) {
        return !Objects.equals(this.blocks, other.blocks)
                || !Objects.equals(this.instructions, other.instructions)
                || !Objects.equals(this.model, other.model)
                || !Objects.equals(this.provider, other.provider)
                || !Objects.equals(this.metadata, other.metadata)
                || !Objects.equals(this.defaultContext, other.defaultContext)
                || !Objects.equals(this.activatedSkillIds, other.activatedSkillIds);
    }
}
