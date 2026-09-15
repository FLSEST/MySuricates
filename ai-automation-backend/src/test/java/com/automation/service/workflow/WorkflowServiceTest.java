package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.dto.request.UpdateWorkflowRequest;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.repository.SkillRepository;
import com.automation.repository.WorkflowRepository;
import com.automation.service.activitylog.ActivityLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowServiceTest {

    @Mock
    private WorkflowRepository workflowRepository;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private ActivityLogService activityLogService;

    @Mock
    private WorkflowMutationGateway mutations;

    @Mock
    private ApplicationEventPublisher eventPublisher;

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
        when(workflowRepository.findAllWithActiveProject()).thenReturn(List.of(testWorkflow));

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
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));

        Workflow result = workflowService.findById("wf-1");

        assertThat(result.getName()).isEqualTo("Test Workflow");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(workflowRepository.findByIdWithSkills("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void create_validRequest_createsWorkflow() {
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("simple").config(Map.of("command", "npm test")).build()
        );
        var request = new CreateWorkflowRequest("proj-1", "New Workflow", "desc", "icon", blocks, null, null, null, null, null, null, null, null);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            w.setId("wf-new");
            return w;
        });

        Workflow result = workflowService.create(request, ChangeSource.USER);

        assertThat(result.getName()).isEqualTo("New Workflow");
        assertThat(result.getProject().getId()).isEqualTo("proj-1");
        assertThat(result.getBlocks()).hasSize(1);        verify(mutations).record(result, null, ChangeSource.USER, null);
    }

    @Test
    void create_withActivatedSkills_activatesSkills() {
        var request = new CreateWorkflowRequest("proj-1", "Skilled", "desc", null,
                List.of(), List.of("skill-1", "skill-2"), null, null, null, null, null, null, null);
        Skill skill1 = Skill.builder().id("skill-1").name("Skill A").category(SkillCategory.CODE).build();
        Skill skill2 = Skill.builder().id("skill-2").name("Skill B").category(SkillCategory.DOCUMENT).build();

        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(skillRepository.findAllById(List.of("skill-1", "skill-2")))
                .thenReturn(List.of(skill1, skill2));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.create(request, ChangeSource.USER);

        assertThat(result.getActivatedSkills()).hasSize(2);
    }

    @Test
    void create_nonExistentProject_throwsException() {
        var request = new CreateWorkflowRequest("unknown", "Name", null, null, List.of(), null, null, null, null, null, null, null, null);
        when(projectRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> workflowService.create(request, ChangeSource.USER))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Project");
    }

    @Test
    void create_rejectsUnknownExecutionMode() {
        List<BlockDefinition> blocks = List.of(BlockDefinition.builder().type("foreach")
                .config(new java.util.HashMap<>(Map.of("execution_mode", "shel", "command", "echo {{ item }}"))).build());
        var request = new CreateWorkflowRequest("proj-1", "Typo", null, null, blocks, null, null, null, null, null, null, null, null);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        assertThatThrownBy(() -> workflowService.create(request, ChangeSource.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown execution_mode 'shel'");
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void update_rejectsUnknownExecutionModeInNewBlocks() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        List<BlockDefinition> blocks = List.of(BlockDefinition.builder().type("while")
                .config(new java.util.HashMap<>(Map.of("execution_mode", "pi"))).build());
        var request = new UpdateWorkflowRequest(null, null, null, blocks, null, null, null, null, null);

        assertThatThrownBy(() -> workflowService.update("wf-1", request, ChangeSource.USER))
                .isInstanceOf(IllegalArgumentException.class);
        verify(workflowRepository, never()).save(any());
    }

    @Test
    void update_updatesOnlyNonNullFields() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateWorkflowRequest("Updated Name", null, null, null, null, null, null, null, null);
        Workflow result = workflowService.update("wf-1", request, ChangeSource.USER);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getDescription()).isEqualTo("A test workflow"); // unchanged
    }

    @Test
    void update_recordsTheChangeWithThePreStateAndTheCallersSource() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        com.automation.dto.WorkflowSnapshot before = com.automation.dto.WorkflowSnapshot.from(testWorkflow);

        var request = new UpdateWorkflowRequest(null, null, null, null, "new instructions", null, null, null, null, "why");
        Workflow result = workflowService.update("wf-1", request, ChangeSource.ASSISTANT);

        verify(mutations).record(result, before, ChangeSource.ASSISTANT, "why");
    }

    @Test
    void restore_isRecordedAsRestore() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));
        var snapshot = new com.automation.dto.WorkflowSnapshot(List.of(), "restored", null, null, Map.of(), Map.of(), List.of());

        Workflow result = workflowService.restoreFromSnapshot("wf-1", snapshot, "Restored from version 2");

        verify(mutations).record(eq(result), any(), eq(ChangeSource.RESTORE), eq("Restored from version 2"));
    }

    // --- effort ---

    @Test
    void create_withEffort_normalizesAndPersists() {
        var request = new CreateWorkflowRequest("proj-1", "WF", null, null, List.of(), null,
                null, null, null, null, null, null, null, "HIGH");
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.create(request, ChangeSource.USER);

        assertThat(result.getEffort()).isEqualTo("high");
    }

    @Test
    void create_invalidEffort_throwsIllegalArgument() {
        var request = new CreateWorkflowRequest("proj-1", "WF", null, null, List.of(), null,
                null, null, null, null, null, null, null, "turbo");
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        assertThatThrownBy(() -> workflowService.create(request, ChangeSource.USER))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("effort");
    }

    @Test
    void update_setsEffort_andBlankClears() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        var set = new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, "xhigh");
        assertThat(workflowService.update("wf-1", set, ChangeSource.USER).getEffort()).isEqualTo("xhigh");

        var clear = new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, "");
        assertThat(workflowService.update("wf-1", clear, ChangeSource.USER).getEffort()).isNull();
    }

    @Test
    void update_nullEffort_keepsExisting() {
        testWorkflow.setEffort("medium");
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateWorkflowRequest("New Name", null, null, null, null, null, null, null, null);
        assertThat(workflowService.update("wf-1", request, ChangeSource.USER).getEffort()).isEqualTo("medium");
    }

    @Test
    void update_invalidEffort_throwsIllegalArgument() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));

        var request = new UpdateWorkflowRequest(null, null, null, null, null, null, null,
                null, null, null, null, null, "banana");
        assertThatThrownBy(() -> workflowService.update("wf-1", request, ChangeSource.USER))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void delete_existingWorkflow_softDeletes() {
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        workflowService.delete("wf-1");

        verify(workflowRepository).save(testWorkflow);
        assertThat(testWorkflow.getDeletedAt()).isNotNull();
    }

    @Test
    void activateSkills_addNewSkills_noDuplicates() {
        Skill existingSkill = Skill.builder().id("skill-1").name("Existing").category(SkillCategory.CODE).build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(existingSkill)));

        Skill newSkill = Skill.builder().id("skill-2").name("New").category(SkillCategory.DOCUMENT).build();

        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(skillRepository.findAllById(List.of("skill-1", "skill-2")))
                .thenReturn(List.of(existingSkill, newSkill));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.activateSkills("wf-1", List.of("skill-1", "skill-2"));

        // skill-1 should not be duplicated
        assertThat(result.getActivatedSkills()).hasSize(2);
    }

    @Test
    void create_withDefaultContext_setsDefaults() {
        var ctx = Map.<String, Object>of("branch", "main", "env", "staging");
        var request = new CreateWorkflowRequest("proj-1", "WF", null, null,
                List.of(), null, null, null, null, null, null, ctx, null);
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> {
            Workflow w = inv.getArgument(0);
            w.setId("wf-new");
            return w;
        });

        Workflow result = workflowService.create(request, ChangeSource.USER);

        assertThat(result.getDefaultContext()).containsEntry("branch", "main");
        assertThat(result.getDefaultContext()).containsEntry("env", "staging");
    }

    @Test
    void update_withDefaultContext_replacesDefaults() {
        testWorkflow.setDefaultContext(new java.util.HashMap<>(Map.of("branch", "main", "env", "prod")));
        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        var request = new UpdateWorkflowRequest(null, null, null, null, null, null, null, null,
                Map.of("branch", "develop"));
        Workflow result = workflowService.update("wf-1", request, ChangeSource.USER);

        assertThat(result.getDefaultContext()).containsEntry("branch", "develop");
        assertThat(result.getDefaultContext()).doesNotContainKey("env"); // replaced, not merged
    }

    @Test
    void deactivateSkill_removesSkill() {
        Skill skill = Skill.builder().id("skill-1").name("Skill").category(SkillCategory.CODE).build();
        testWorkflow.setActivatedSkills(new ArrayList<>(List.of(skill)));

        when(workflowRepository.findByIdWithSkills("wf-1")).thenReturn(Optional.of(testWorkflow));
        when(workflowRepository.save(any(Workflow.class))).thenAnswer(inv -> inv.getArgument(0));

        Workflow result = workflowService.deactivateSkill("wf-1", "skill-1");

        assertThat(result.getActivatedSkills()).isEmpty();
    }
}
