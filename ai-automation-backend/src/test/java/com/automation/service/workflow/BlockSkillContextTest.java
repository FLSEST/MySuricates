package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.Project;
import com.automation.entity.ProjectType;
import com.automation.entity.Skill;
import com.automation.service.cli.CommandExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class BlockSkillContextTest {

    @Mock private CommandExecutorService commandExecutorService;

    @InjectMocks private BlockSkillContext skillContext;

    private Project project;
    private Skill workflowSkill;
    private Skill blockSkill;

    @BeforeEach
    void setUp() {
        project = Project.builder().id("p-1").name("P").type(ProjectType.CODE).path("/workspace/test").build();
        workflowSkill = Skill.builder().id("s-wf").name("Workflow Skill").build();
        blockSkill = Skill.builder().id("s-block").name("Block Skill").build();
    }

    @SuppressWarnings("unchecked")
    private List<Skill> captureWrittenSkills() {
        ArgumentCaptor<List<Skill>> captor = ArgumentCaptor.forClass(List.class);
        verify(commandExecutorService).writeSkillContextToProject(eq("/workspace/test"), captor.capture());
        return captor.getValue();
    }

    @Test
    void applyForBlock_withExtraSkills_writesMergedListOnce() {
        BlockDefinition block = BlockDefinition.builder().type("simple")
                .skillIds(List.of("s-wf", "s-block")).build();

        boolean hasBlockSkills = skillContext.applyForBlock(block, project, List.of(workflowSkill),
                Map.of("s-block", blockSkill));

        assertThat(hasBlockSkills).isTrue();
        // workflow skills first, block skill appended, no duplicate for the id both sides share
        assertThat(captureWrittenSkills()).containsExactly(workflowSkill, blockSkill);
    }

    @Test
    void applyForBlock_unknownBlockSkillId_isSkipped() {
        BlockDefinition block = BlockDefinition.builder().type("simple")
                .skillIds(List.of("s-deleted")).build();

        boolean hasBlockSkills = skillContext.applyForBlock(block, project, List.of(workflowSkill), Map.of());

        assertThat(hasBlockSkills).isTrue();
        assertThat(captureWrittenSkills()).containsExactly(workflowSkill);
    }

    @Test
    void applyForBlock_withoutExtraSkills_writesNothingAndReturnsFalse() {
        BlockDefinition block = BlockDefinition.builder().type("simple").skillIds(List.of()).build();

        boolean hasBlockSkills = skillContext.applyForBlock(block, project, List.of(workflowSkill), Map.of());

        assertThat(hasBlockSkills).isFalse();
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void applyForBlock_nullSkillIds_writesNothingAndReturnsFalse() {
        BlockDefinition block = BlockDefinition.builder().type("simple").skillIds(null).build();

        boolean hasBlockSkills = skillContext.applyForBlock(block, project, List.of(workflowSkill), Map.of());

        assertThat(hasBlockSkills).isFalse();
        verifyNoInteractions(commandExecutorService);
    }

    @Test
    void restore_writesWorkflowSkills() {
        skillContext.restore(project, List.of(workflowSkill));

        assertThat(captureWrittenSkills()).containsExactly(workflowSkill);
    }
}
