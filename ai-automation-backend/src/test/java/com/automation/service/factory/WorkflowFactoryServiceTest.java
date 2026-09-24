package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import com.automation.dto.request.WorkflowFactoryRequest;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.ProjectRepository;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.WorkflowTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowFactoryServiceTest {

    @Mock
    private ClaudeWorkflowGenerator claudeGenerator;

    @Mock
    private CodexWorkflowGenerator codexGenerator;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowTemplateService templateService;

    @Mock
    private TestingService testingService;

    @Mock
    private ProjectRepository projectRepository;

    @Mock
    private AgentInstructionsService agentInstructionsService;

    private ObjectMapper objectMapper = new ObjectMapper();
    private FactoryConfig factoryConfig;
    private WorkflowFactoryService factoryService;

    private Project testProject;

    @BeforeEach
    void setUp() {
        factoryConfig = new FactoryConfig();
        factoryConfig.setProvider("CLAUDE");
        factoryConfig.setTimeoutSeconds(600);

        FactoryGeneratorResolver generatorResolver =
                new FactoryGeneratorResolver(claudeGenerator, codexGenerator, factoryConfig);
        FactoryPromptBuilder promptBuilder = new FactoryPromptBuilder(agentInstructionsService);
        FactoryResponseParser responseParser = new FactoryResponseParser(objectMapper);
        FactoryVerificationService verificationService = new FactoryVerificationService(
                workflowService, testingService, objectMapper, promptBuilder, responseParser, generatorResolver);
        factoryService = new WorkflowFactoryService(
                generatorResolver, factoryConfig,
                workflowService, templateService, verificationService,
                projectRepository, objectMapper, promptBuilder, responseParser
        );

        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .build();

        lenient().when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));
        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
    }

    // ── generateWorkflow ───────────────────────────────

    @Test
    void generateWorkflow_validJson_createsWorkflow() throws IOException {
        String claudeResponse = """
                {
                  "name": "Code Review",
                  "description": "Reviews code quality",
                  "icon": "🔍",
                  "blocks": [
                    { "type": "simple", "config": { "command": "Review the code" } },
                    { "type": "shell", "config": { "command": "npm test" } }
                  ],
                  "defaultContext": { "branch": "main" }
                }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow createdWorkflow = Workflow.builder()
                .id("wf-1").name("Code Review").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Code review workflow", "Reviews code", "CODE", "proj-1",
                null, false, false, 2, null);

        Workflow result = factoryService.generateWorkflow(request);

        assertThat(result.getName()).isEqualTo("Code Review");
        verify(workflowService).create(any(), eq(ChangeSource.FACTORY));
    }

    @Test
    void generateWorkflow_jsonInCodeFences_extractedCorrectly() throws IOException {
        String claudeResponse = """
                Here's the workflow:
                ```json
                {
                  "name": "Deploy",
                  "blocks": [
                    { "type": "shell", "config": { "command": "deploy.sh" } }
                  ]
                }
                ```
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow createdWorkflow = Workflow.builder().id("wf-1").name("Deploy").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Deploy", null, null, "proj-1", null, false, false, 2, null);

        Workflow result = factoryService.generateWorkflow(request);

        assertThat(result).isNotNull();
        verify(workflowService).create(any(), eq(ChangeSource.FACTORY));
    }

    @Test
    void generateWorkflow_noJsonInResponse_throwsIOException() throws IOException {
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn("No JSON here");

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, null);

        assertThatThrownBy(() -> factoryService.generateWorkflow(request))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("No valid JSON");
    }

    @Test
    void generateWorkflow_projectNotFound_throwsResourceNotFound() {
        when(projectRepository.findById("missing")).thenReturn(Optional.empty());

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "missing", null, false, false, 2, null);

        assertThatThrownBy(() -> factoryService.generateWorkflow(request))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void generateWorkflow_usesCodexProvider() throws IOException {
        factoryConfig.setProvider("CODEX");

        String codexResponse = """
                { "name": "Codex WF", "blocks": [{ "type": "simple", "config": { "command": "test" } }] }
                """;
        when(codexGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(codexResponse);

        Workflow createdWorkflow = Workflow.builder().id("wf-1").name("Codex WF").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, null);

        factoryService.generateWorkflow(request);

        verify(codexGenerator).generate(anyString(), anyString(), eq(600));
        verify(claudeGenerator, never()).generate(anyString(), anyString(), anyInt());
    }

    @Test
    void generateWorkflow_requestProviderOverridesConfig() throws IOException {
        // Config defaults to CLAUDE, but request says CODEX
        String codexResponse = """
                { "name": "Test", "blocks": [{ "type": "simple", "config": { "command": "test" } }] }
                """;
        when(codexGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(codexResponse);

        Workflow createdWorkflow = Workflow.builder().id("wf-1").name("Test").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, "CODEX");

        factoryService.generateWorkflow(request);

        verify(codexGenerator).generate(anyString(), anyString(), eq(600));
    }

    @Test
    void generateWorkflow_unknownProvider_throwsIllegalArgument() {
        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, "GEMINI");

        assertThatThrownBy(() -> factoryService.generateWorkflow(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown factory provider");
    }

    @Test
    void generateWorkflow_mergesTestInputsIntoDefaultContext() throws IOException {
        String claudeResponse = """
                {
                  "name": "WF",
                  "blocks": [{ "type": "simple", "config": { "command": "test" } }],
                  "defaultContext": { "branch": "main" }
                }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow createdWorkflow = Workflow.builder().id("wf-1").name("WF").project(testProject).build();
        when(workflowService.create(argThat(req ->
                req.defaultContext().containsKey("branch") &&
                req.defaultContext().containsKey("test_var")
        ), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1",
                Map.of("test_var", "test_value"),
                false, false, 2, null);

        factoryService.generateWorkflow(request);

        verify(workflowService).create(argThat(req ->
                req.defaultContext().get("test_var").equals("test_value") &&
                req.defaultContext().get("branch").equals("main")
        ), eq(ChangeSource.FACTORY));
    }

    @Test
    void generateWorkflow_setsFactoryMetadata() throws IOException {
        String claudeResponse = """
                { "name": "WF", "blocks": [{ "type": "simple", "config": { "command": "test" } }] }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow createdWorkflow = Workflow.builder().id("wf-1").name("WF").project(testProject).build();
        when(workflowService.create(argThat(req ->
                req.metadata() != null &&
                Boolean.TRUE.equals(req.metadata().get("factoryGenerated")) &&
                "Test topic".equals(req.metadata().get("factoryTopic"))
        ), eq(ChangeSource.FACTORY))).thenReturn(createdWorkflow);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test topic", null, "CODE", "proj-1", null, false, false, 2, null);

        factoryService.generateWorkflow(request);

        verify(workflowService).create(argThat(req ->
                req.metadata().get("factoryCategory").equals("CODE")
        ), eq(ChangeSource.FACTORY));
    }

    // ── runFactory ─────────────────────────────────────

    @Test
    void runFactory_successfulPipeline_returnsVerified() throws IOException {
        String claudeResponse = """
                { "name": "Pipeline WF", "blocks": [{ "type": "simple", "config": { "command": "test" } }] }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow workflow = Workflow.builder().id("wf-1").name("Pipeline WF").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(workflow);

        // Deploy & verify succeeds
        JsonNode completedRun = objectMapper.createObjectNode()
                .put("status", "COMPLETED");
        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(eq("Pipeline WF"), eq("Test Project"), any()))
                .thenReturn("test-run-1");
        when(testingService.pollTestRun("test-run-1")).thenReturn(completedRun);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Pipeline test", null, null, "proj-1", null, false, false, 2, null);

        WorkflowFactoryResult result = factoryService.runFactory(request);

        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.testStatus()).isEqualTo("PASSED");
        assertThat(result.workflowId()).isEqualTo("wf-1");
        verify(testingService).reportTestResult("Pipeline WF", "Test Project", "PASSED");
    }

    @Test
    void runFactory_testFails_noAutoFix_returnsFailed() throws IOException {
        String claudeResponse = """
                { "name": "Fail WF", "blocks": [{ "type": "shell", "config": { "command": "exit 1" } }] }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow workflow = Workflow.builder().id("wf-1").name("Fail WF").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(workflow);

        // Deploy & verify fails
        JsonNode failedRun = objectMapper.createObjectNode()
                .put("status", "FAILED")
                .put("error", "Command failed");
        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1")).thenReturn(failedRun);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, null);

        WorkflowFactoryResult result = factoryService.runFactory(request);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.testStatus()).isEqualTo("FAILED");
    }

    @Test
    void runFactory_autoPromote_createsTemplate() throws IOException {
        String claudeResponse = """
                { "name": "Promote WF", "blocks": [{ "type": "simple", "config": { "command": "test" } }] }
                """;
        when(claudeGenerator.generate(anyString(), anyString(), eq(600))).thenReturn(claudeResponse);

        Workflow workflow = Workflow.builder().id("wf-1").name("Promote WF").project(testProject).build();
        when(workflowService.create(any(), eq(ChangeSource.FACTORY))).thenReturn(workflow);

        JsonNode completedRun = objectMapper.createObjectNode().put("status", "COMPLETED");
        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1")).thenReturn(completedRun);

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, true, false, 2, null);

        WorkflowFactoryResult result = factoryService.runFactory(request);

        assertThat(result.promotedAsTemplate()).isTrue();
        verify(templateService).createFromWorkflow("wf-1");
    }

    @Test
    void runFactory_generateThrows_returnsFailedResult() {
        when(projectRepository.findById("proj-1")).thenReturn(Optional.of(testProject));

        WorkflowFactoryRequest request = new WorkflowFactoryRequest(
                "Test", null, null, "proj-1", null, false, false, 2, null);

        // claudeGenerator.generate will throw because it's not stubbed
        try {
            when(claudeGenerator.generate(anyString(), anyString(), anyInt()))
                    .thenThrow(new IOException("Claude unavailable"));
        } catch (IOException e) {
            // won't happen in mock setup
        }

        WorkflowFactoryResult result = factoryService.runFactory(request);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.testError()).contains("Claude unavailable");
    }

    // ── promoteToTemplate ──────────────────────────────

    @Test
    void promoteToTemplate_createsTemplateAndReturnsPromoted() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Promote WF").testStatus("PASSED").build();
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        WorkflowFactoryResult result = factoryService.promoteToTemplate("wf-1");

        assertThat(result.status()).isEqualTo("PROMOTED");
        assertThat(result.promotedAsTemplate()).isTrue();
        verify(templateService).createFromWorkflow("wf-1");
    }
}
