package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.service.cli.CommandExecutorService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Per-block skill context: merges the block's own skills with the workflow-level ones and writes
 * them into the project before the block runs; {@link #restore} puts the workflow baseline back.
 *
 * <p>Extracted 1:1 from {@code WorkflowExecutor}'s block loop (clean-code block 3c).
 */
@Component
@RequiredArgsConstructor
public class BlockSkillContext {

    private final CommandExecutorService commandExecutorService;

    /**
     * Writes the merged skill list (workflow skills first, block-only skills appended) when the
     * block declares its own skills.
     *
     * @return true when the block carries extra skills — the caller must call {@link #restore}
     *         afterwards to put the workflow baseline back.
     */
    public boolean applyForBlock(BlockDefinition block, Project project, List<Skill> workflowSkills,
                                 Map<String, Skill> blockSkillLookup) {
        boolean hasBlockSkills = block.getSkillIds() != null && !block.getSkillIds().isEmpty();
        if (hasBlockSkills) {
            List<Skill> merged = new ArrayList<>(workflowSkills);
            Set<String> existingIds = workflowSkills.stream().map(Skill::getId).collect(Collectors.toSet());
            for (String id : block.getSkillIds()) {
                if (!existingIds.contains(id)) {
                    Skill extra = blockSkillLookup.get(id);
                    if (extra != null) {
                        merged.add(extra);
                    }
                }
            }
            commandExecutorService.writeSkillContextToProject(project.getPath(), merged);
        }
        return hasBlockSkills;
    }

    /** Restores the workflow-level skill context after a block with extra skills. */
    public void restore(Project project, List<Skill> workflowSkills) {
        commandExecutorService.writeSkillContextToProject(project.getPath(), workflowSkills);
    }
}
