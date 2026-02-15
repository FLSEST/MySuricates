package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SkillRepository skillRepository;

    @InjectMocks
    private WorkflowService workflowService;

    private Project testProject;
    private Workflow testWorkflow;

    @BeforeEach
    void setUp() {
        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .type(ProjectType.CODE)
                .path("/workspace/test")
                .build();

        testWorkflow = Workflow.builder()
                .id("wf-1")
                .project(testProject)
                .name("Test Workflow")
                .description("A test workflow")
                .blocks(new ArrayList<>(List.of(
                        BlockDefinition.builder().type("simple").config(Map.of("command", "echo test")).build()
                )))
                .activatedSkills(new ArrayList<>())
                .build();
    }

    @Test
    void findAll_returnsAllWorkflows() {
        when(workflowRepository.findAll()).thenReturn(List.of(testWorkflow));

        List<Workflow> result = workflowService.findAll();

        assertThat(result).hasSize(1);
    }

    @Test
    void findByProjectId_returnsMatchingWorkflows() {
        when(workflowRepository.findByProjectId("proj-1")).thenReturn(List.of(testWorkflow));

        List<Workflow> result = workflowService.findByProjectId("proj-1");

        assertThat(result).hasSize(1);
    }

    @Test
    void findById_existingWorkflow_returnsWorkflow() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        Workflow result = workflowService.findById("wf-1");

        assertThat(result.getName()).isEqualTo("Test Workflow");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(workflowRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validRequest_createsWorkflow() {
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("simple").config(Map.of("command", "npm test")).build()
        );
        var request = new CreateWorkflowRequest("proj-1", "New Workflow", "desc", "icon", blocks, null);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            w.setId("wf-new");
            return w;
        });

        Workflow result = workflowService.create(request);

        assertThat(result.getName()).isEqualTo("New Workflow");
        assertThat(result.getProject().getId()).isEqualTo("proj-1");
        assertThat(result.getBlocks()).hasSize(1);
    }

    @Test
    void create_withActivatedSkills_activatesSkills() {
        var request = new CreateWorkflowRequest("proj-1", "Skilled", "desc", null,
                List.of(), List.of("skill-1", "skill-2"));
        Skill skill1 = Skill.builder().id("skill-1").name("Skill A").category(SkillCategory.CODE).build();
        Skill skill2 = Skill.builder().id("skill-2").name("Skill B").category(SkillCategory.DOCUMENT).build();

        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(skillRepository.findAllById(List.of("skill-1", "skill-2")))
                .thenReturn(List.of(skill1, skill2));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.create(request);

        assertThat(result.getActivatedSkills()).hasSize(2);
    }

    @Test
    void create_nonExistentProject_throwsException() {
        var request = new CreateWorkflowRequest("unknown", "Name", null, null, List.of(), null);
        when(projectRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.create(request))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project");
    }

    @Test
    void update_updatesOnlyNonNullFields() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateWorkflowRequest("Updated Name", null, null, null);
        Workflow result = workflowService.update("wf-1", request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getDescription()).isEqualTo("A test workflow"); // unchanged
    }

    @Test
    void delete_existingWorkflow_deletes() {
        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));

        workflowService.delete("wf-1");

        verify(workflowRepository).delete(testWorkflow);
    }

    @Test
    void activateSkills_addNewSkills_noDuplicates() {
        Skill existingSkill = Skill.builder().id("skill-1").name("Existing").category(SkillCategory.CODE).build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(existingSkill)));

        Skill newSkill = Skill.builder().id("skill-2").name("New").category(SkillCategory.DOCUMENT).build();

        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(skillRepository.findAllById(List.of("skill-1", "skill-2")))
                .thenReturn(List.of(existingSkill, newSkill));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.activateSkills("wf-1", List.of("skill-1", "skill-2"));

        // skill-1 should not be duplicated
        assertThat(result.getActivatedSkills()).hasSize(2);
    }

    @Test
    void deactivateSkill_removesSkill() {
        Skill skill = Skill.builder().id("skill-1").name("Skill").category(SkillCategory.CODE).build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(skill)));

        when(workflowRepository.findById("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.deactivateSkill("wf-1", "skill-1");

        assertThat(result.getActivatedSkills()).isEmpty();
    }
}
