package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.SkillRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Copies what a run needs from the lazy workflow graph while the JPA session is still open, so the
 * block loop on its own thread never touches a detached entity (split out of {@link WorkflowExecutor},
 * TODO 346193fc).
 */
@Component
@RequiredArgsConstructor
@Slf4j
class WorkflowSnapshotLoader {

    /** Detached copy of everything the async loop needs — taken while the JPA session is open. */
    record WorkflowSnapshot(Project project, List<BlockDefinition> blocks, List<Skill> skills,
                            String instructions, String model, String provider, String effort,
                            Map<String, Object> workflowMeta, String templateId,
                            Map<String, Skill> blockSkillLookup) {}

    private final SkillRepository skillRepository;

    /**
     * Eagerly copies the lazy workflow graph and pre-resolves per-block skill IDs to entities (one
     * DB query). Must run while the JPA session is open; callers catch so a failure fails the run.
     */
    WorkflowSnapshot load(Workflow workflow) {
        List<BlockDefinition> blocks = List.copyOf(workflow.getBlocks());
        List<Skill> skills = List.copyOf(workflow.getActivatedSkills());
        Set<String> workflowSkillIds = skills.stream().map(Skill::getId).collect(Collectors.toSet());
        Set<String> blockSkillIds = blocks.stream()
                .filter(b -> b.getSkillIds() != null)
                .flatMap(b -> b.getSkillIds().stream())
                .filter(id -> !workflowSkillIds.contains(id))
                .collect(Collectors.toSet());
        Map<String, Skill> blockSkillLookup = blockSkillIds.isEmpty()
                ? Map.of()
                : skillRepository.findAllById(blockSkillIds).stream()
                        .collect(Collectors.toMap(Skill::getId, s -> s));
        if (!blockSkillIds.isEmpty() && blockSkillLookup.size() < blockSkillIds.size()) {
            Set<String> missing = new HashSet<>(blockSkillIds);
            missing.removeAll(blockSkillLookup.keySet());
            log.warn("Per-block skill IDs not found (deleted?): {}", missing);
        }
        return new WorkflowSnapshot(workflow.getProject(), blocks, skills, workflow.getInstructions(),
                workflow.getModel(), workflow.getProvider(), workflow.getEffort(),
                RunWorkspaceService.policy(workflow.getMetadata(), blocks), workflow.getTemplateId(), blockSkillLookup);
    }
}
