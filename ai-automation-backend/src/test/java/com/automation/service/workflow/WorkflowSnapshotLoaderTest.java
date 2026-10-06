package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.Skill;
import com.automation.entity.Workflow;
import com.automation.repository.SkillRepository;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkflowSnapshotLoaderTest {

    private final SkillRepository skillRepository = mock(SkillRepository.class);
    private final WorkflowSnapshotLoader loader = new WorkflowSnapshotLoader(skillRepository);

    @Test
    void copiesTheWorkflowGraphAndSettings() {
        Project project = Project.builder().id("p-1").build();
        Skill skill = skill("skill-wf");
        Workflow workflow = workflow(project, List.of(block("simple", null)), List.of(skill));
        workflow.setInstructions("be brief");
        workflow.setModel("sonnet");
        workflow.setProvider("CLAUDE");
        workflow.setEffort("high");
        workflow.setTemplateId("tpl-1");

        var snapshot = loader.load(workflow);

        assertThat(snapshot.project()).isSameAs(project);
        assertThat(snapshot.blocks()).hasSize(1).isUnmodifiable();
        assertThat(snapshot.skills()).containsExactly(skill).isUnmodifiable();
        assertThat(snapshot.instructions()).isEqualTo("be brief");
        assertThat(snapshot.model()).isEqualTo("sonnet");
        assertThat(snapshot.provider()).isEqualTo("CLAUDE");
        assertThat(snapshot.effort()).isEqualTo("high");
        assertThat(snapshot.templateId()).isEqualTo("tpl-1");
        assertThat(snapshot.blockSkillLookup()).isEmpty();
        verify(skillRepository, never()).findAllById(any());
    }

    @Test
    void laterChangesToTheWorkflowDoNotReachTheSnapshot() {
        Workflow workflow = workflow(null, List.of(block("simple", null)), List.of());

        var snapshot = loader.load(workflow);
        workflow.getBlocks().add(block("shell", null));

        assertThat(snapshot.blocks()).hasSize(1);
    }

    @Test
    void resolvesOnlyBlockSkillsThatAreNotAlreadyWorkflowSkills() {
        Skill workflowSkill = skill("skill-wf");
        Skill blockSkill = skill("skill-block");
        Workflow workflow = workflow(null,
                List.of(block("simple", List.of("skill-wf", "skill-block"))), List.of(workflowSkill));
        when(skillRepository.findAllById(Set.of("skill-block"))).thenReturn(List.of(blockSkill));

        var snapshot = loader.load(workflow);

        assertThat(snapshot.blockSkillLookup()).containsExactly(Map.entry("skill-block", blockSkill));
    }

    @Test
    void deletedBlockSkillsAreLeftOutInsteadOfFailingTheRun() {
        Skill kept = skill("skill-kept");
        Workflow workflow = workflow(null,
                List.of(block("simple", List.of("skill-kept", "skill-deleted"))), List.of());
        when(skillRepository.findAllById(Set.of("skill-kept", "skill-deleted"))).thenReturn(List.of(kept));

        var snapshot = loader.load(workflow);

        assertThat(snapshot.blockSkillLookup()).containsOnlyKeys("skill-kept");
    }

    @Test
    void takesTheWorkspacePolicyFromTheFirstBlock() {
        BlockDefinition first = block("shell", null);
        first.getConfig().put("workspace", Map.of("mode", "commit"));
        Workflow workflow = workflow(null, List.of(first), List.of());

        var snapshot = loader.load(workflow);

        assertThat(snapshot.workflowMeta()).containsEntry("workspace", Map.of("mode", "commit"));
    }

    private static Workflow workflow(Project project, List<BlockDefinition> blocks, List<Skill> skills) {
        return Workflow.builder().id("wf-1").name("wf").project(project)
                .blocks(new ArrayList<>(blocks)).activatedSkills(new ArrayList<>(skills)).build();
    }

    private static BlockDefinition block(String type, List<String> skillIds) {
        return BlockDefinition.builder().type(type).config(new HashMap<>())
                .skillIds(skillIds == null ? null : new ArrayList<>(skillIds)).build();
    }

    private static Skill skill(String id) {
        return Skill.builder().id(id).name(id).build();
    }
}
