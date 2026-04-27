package com.automation.service.chain;

import com.automation.dto.request.CreateChainRequest;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.BlockDefinition;
import com.automation.entity.Workflow;
import com.automation.entity.WorkflowChain;
import com.automation.entity.WorkflowTemplate;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainTemplateServiceTest {

    @Mock
    private WorkflowTemplateService workflowTemplateService;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private ChainService chainService;

    @InjectMocks
    private ChainTemplateService chainTemplateService;

    @Captor
    private ArgumentCaptor<CreateChainRequest> chainRequestCaptor;

    @Captor
    private ArgumentCaptor<CreateWorkflowRequest> workflowRequestCaptor;

    @Nested
    class FindTests {

        @Test
        void findAll_returns6BuiltInTemplates() {
            List<BuiltInChainTemplate> templates = chainTemplateService.findAll();
            assertThat(templates).hasSize(6);
        }

        @Test
        void findAll_eachTemplateHasRequiredFields() {
            for (BuiltInChainTemplate template : chainTemplateService.findAll()) {
                assertThat(template.id()).isNotBlank();
                assertThat(template.name()).containsKeys("de", "en");
                assertThat(template.description()).containsKeys("de", "en");
                assertThat(template.icon()).isNotBlank();
                assertThat(template.entries()).isNotEmpty();
            }
        }

        @Test
        void findById_validId_returnsCorrectTemplate() {
            BuiltInChainTemplate template = chainTemplateService.findById("ci-cd-pipeline-chain");
            assertThat(template.id()).isEqualTo("ci-cd-pipeline-chain");
            assertThat(template.entries()).hasSize(2);
        }

        @Test
        void findById_invalidId_throwsNoSuchElementException() {
            assertThatThrownBy(() -> chainTemplateService.findById("nonexistent"))
                    .isInstanceOf(NoSuchElementException.class)
                    .hasMessageContaining("nonexistent");
        }
    }

    @Nested
    class InstallTests {

        private WorkflowTemplate mockWfTemplate;

        @BeforeEach
        void setUp() {
            mockWfTemplate = new WorkflowTemplate();
            mockWfTemplate.setId("template-1");
            mockWfTemplate.setName(Map.of("de", "Test-Workflow", "en", "Test Workflow"));
            mockWfTemplate.setIcon("rocket");
            mockWfTemplate.setBlocks(List.of(BlockDefinition.builder().type("simple").build()));
            mockWfTemplate.setInstructions("Do something");
            mockWfTemplate.setModel("claude-sonnet-4-6");
        }

        @Test
        void install_validTemplate_createsWorkflowsAndChain() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenAnswer(inv -> {
                CreateWorkflowRequest req = inv.getArgument(0);
                return Workflow.builder().id("wf-" + req.name()).name(req.name()).build();
            });
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-1").name("CI/CD Pipeline").build());

            // ci-cd-pipeline-chain has 2 entries
            WorkflowChain result = chainTemplateService.install("ci-cd-pipeline-chain", "proj-1", null);

            assertThat(result.getId()).isEqualTo("chain-1");
            verify(workflowTemplateService, times(2)).findById(any());
            verify(workflowService, times(2)).create(any());
            verify(chainService).create(chainRequestCaptor.capture());

            CreateChainRequest chainReq = chainRequestCaptor.getValue();
            assertThat(chainReq.entries()).hasSize(2);
            assertThat(chainReq.stopOnFailure()).isTrue();
        }

        @Test
        void install_threeEntryTemplate_createsThreeWorkflows() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenAnswer(inv -> {
                CreateWorkflowRequest req = inv.getArgument(0);
                return Workflow.builder().id("wf-" + req.name()).name(req.name()).build();
            });
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-2").name("Code Health").build());

            // code-health-chain has 3 entries
            chainTemplateService.install("code-health-chain", "proj-1", "Custom Name");

            verify(workflowService, times(3)).create(any());
        }

        @Test
        void install_withCustomChainName_usesCustomName() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenReturn(
                    Workflow.builder().id("wf-1").name("Test").build());
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-1").name("My Chain").build());

            chainTemplateService.install("ci-cd-pipeline-chain", "proj-1", "My Chain");

            verify(chainService).create(chainRequestCaptor.capture());
            assertThat(chainRequestCaptor.getValue().name()).isEqualTo("My Chain");
        }

        @Test
        void install_withNullChainName_usesTemplateName() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenReturn(
                    Workflow.builder().id("wf-1").name("Test").build());
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-1").name("CI/CD Pipeline").build());

            chainTemplateService.install("ci-cd-pipeline-chain", "proj-1", null);

            verify(chainService).create(chainRequestCaptor.capture());
            assertThat(chainRequestCaptor.getValue().name()).isEqualTo("CI/CD Pipeline");
        }

        @Test
        void install_withBlankChainName_usesTemplateName() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenReturn(
                    Workflow.builder().id("wf-1").name("Test").build());
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-1").name("CI/CD Pipeline").build());

            chainTemplateService.install("ci-cd-pipeline-chain", "proj-1", "  ");

            verify(chainService).create(chainRequestCaptor.capture());
            assertThat(chainRequestCaptor.getValue().name()).isEqualTo("CI/CD Pipeline");
        }

        @Test
        void install_passesCorrectProjectId() {
            when(workflowTemplateService.findById(any())).thenReturn(mockWfTemplate);
            when(workflowService.create(any())).thenReturn(
                    Workflow.builder().id("wf-1").name("Test").build());
            when(chainService.create(any())).thenReturn(
                    WorkflowChain.builder().id("chain-1").build());

            chainTemplateService.install("ci-cd-pipeline-chain", "proj-42", null);

            verify(workflowService, times(2)).create(workflowRequestCaptor.capture());
            for (CreateWorkflowRequest req : workflowRequestCaptor.getAllValues()) {
                assertThat(req.projectId()).isEqualTo("proj-42");
            }
        }

        @Test
        void install_templateNotFound_throwsNoSuchElementException() {
            assertThatThrownBy(() -> chainTemplateService.install("nonexistent", "proj-1", null))
                    .isInstanceOf(NoSuchElementException.class);
        }
    }
}
