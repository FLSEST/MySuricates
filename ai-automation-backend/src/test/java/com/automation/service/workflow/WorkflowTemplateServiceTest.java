package com.automation.service.workflow;

import com.automation.dto.request.CreateWorkflowTemplateRequest;
import com.automation.dto.request.UpdateWorkflowTemplateRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowTemplate;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.WorkflowTemplateRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowTemplateServiceTest {

    @Mock
    private WorkflowTemplateRepository templateRepository;

    @Mock
    private WorkflowService workflowService;

    @InjectMocks
    private WorkflowTemplateService templateService;

    // --- findAll ---

    @Test
    void findAll_returnsSortedTemplates() {
        WorkflowTemplate t1 = WorkflowTemplate.builder().id("t-1").sortOrder(0).build();
        WorkflowTemplate t2 = WorkflowTemplate.builder().id("t-2").sortOrder(1).build();
        when(templateRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(t1, t2));

        List<WorkflowTemplate> result = templateService.findAll();

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getId()).isEqualTo("t-1");
    }

    // --- findById ---

    @Test
    void findById_existing_returnsTemplate() {
        WorkflowTemplate template = WorkflowTemplate.builder().id("t-1").build();
        when(templateRepository.findById("t-1")).thenReturn(Optional.of(template));

        WorkflowTemplate result = templateService.findById("t-1");

        assertThat(result.getId()).isEqualTo("t-1");
    }

    @Test
    void findById_nonExistent_throwsException() {
        when(templateRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.findById("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // --- create ---

    @Test
    void create_setsBuiltInToFalse() {
        CreateWorkflowTemplateRequest request = new CreateWorkflowTemplateRequest(
                Map.of("de", "Test", "en", "Test"),
                Map.of("de", "Beschreibung", "en", "Description"),
                "cog", "automation",
                List.of(BlockDefinition.builder().type("simple").build()),
                "Instructions", "claude-sonnet-4-6");

        when(templateRepository.save(any(WorkflowTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowTemplate result = templateService.create(request);

        assertThat(result.isBuiltIn()).isFalse();
        assertThat(result.getName()).containsEntry("de", "Test");
        assertThat(result.getCategory()).isEqualTo("automation");
    }

    // --- update ---

    @Test
    void update_partialUpdate_onlyChangesProvidedFields() {
        WorkflowTemplate existing = WorkflowTemplate.builder()
                .id("t-1")
                .name(Map.of("de", "Alt", "en", "Old"))
                .icon("cog")
                .category("automation")
                .build();
        when(templateRepository.findById("t-1")).thenReturn(Optional.of(existing));
        when(templateRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateWorkflowTemplateRequest request = new UpdateWorkflowTemplateRequest(
                Map.of("de", "Neu", "en", "New"), null, null, null, null, null, null, null);

        WorkflowTemplate result = templateService.update("t-1", request);

        assertThat(result.getName()).containsEntry("de", "Neu");
        assertThat(result.getIcon()).isEqualTo("cog");
        assertThat(result.getCategory()).isEqualTo("automation");
    }

    // --- delete ---

    @Test
    void delete_builtInTemplate_throwsException() {
        WorkflowTemplate builtIn = WorkflowTemplate.builder()
                .id("t-builtin").builtIn(true).build();
        when(templateRepository.findById("t-builtin")).thenReturn(Optional.of(builtIn));

        assertThatThrownBy(() -> templateService.delete("t-builtin"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("built-in");
    }

    @Test
    void delete_customTemplate_deletes() {
        WorkflowTemplate custom = WorkflowTemplate.builder()
                .id("t-custom").builtIn(false).build();
        when(templateRepository.findById("t-custom")).thenReturn(Optional.of(custom));

        templateService.delete("t-custom");

        verify(templateRepository).delete(custom);
    }

    // --- createFromWorkflow ---

    @Test
    void createFromWorkflow_copiesWorkflowFields() {
        Workflow workflow = Workflow.builder()
                .id("wf-1")
                .name("My Workflow")
                .description("A workflow")
                .icon("zap")
                .instructions("Do things")
                .model("claude-sonnet-4-6")
                .blocks(List.of(BlockDefinition.builder().type("shell").build()))
                .build();
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(templateRepository.save(any(WorkflowTemplate.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        WorkflowTemplate result = templateService.createFromWorkflow("wf-1");

        assertThat(result.isBuiltIn()).isFalse();
        assertThat(result.getName()).containsEntry("de", "My Workflow").containsEntry("en", "My Workflow");
        assertThat(result.getIcon()).isEqualTo("zap");
        assertThat(result.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(result.getBlocks()).hasSize(1);
    }
}
