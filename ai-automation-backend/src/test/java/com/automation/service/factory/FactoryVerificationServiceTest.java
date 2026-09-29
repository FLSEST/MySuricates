package com.automation.service.factory;

import com.automation.config.FactoryConfig;
import com.automation.dto.response.WorkflowFactoryResult;
import com.automation.entity.BlockDefinition;
import com.automation.entity.ChangeSource;
import com.automation.entity.Project;
import com.automation.entity.Workflow;
import com.automation.service.staging.TestingService;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.automation.service.activitylog.ActivityLogService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class FactoryVerificationServiceTest {

    @Mock
    private ClaudeWorkflowGenerator claudeGenerator;

    @Mock
    private CodexWorkflowGenerator codexGenerator;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private TestingService testingService;

    @Mock
    private AgentInstructionsService agentInstructionsService;

    @Mock
    private ActivityLogService activityLogService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private FactoryVerificationService verificationService;

    private Project testProject;

    @BeforeEach
    void setUp() {
        FactoryConfig factoryConfig = new FactoryConfig();
        factoryConfig.setProvider("CLAUDE");
        factoryConfig.setTimeoutSeconds(600);

        verificationService = new FactoryVerificationService(
                workflowService, testingService, objectMapper,
                new FactoryPromptBuilder(agentInstructionsService),
                new FactoryResponseParser(objectMapper),
                new FactoryGeneratorResolver(claudeGenerator, codexGenerator, factoryConfig),
                new GeneratedWorkflowCheck(new WorkflowAnalyzer(), objectMapper, activityLogService));

        testProject = Project.builder()
                .id("proj-1")
                .name("Test Project")
                .build();

        lenient().when(agentInstructionsService.buildInstructionPrefix(any())).thenReturn("");
    }

    // ── deployAndVerify ────────────────────────────────

    @Test
    void deployAndVerify_executionError_returnsFailedResult() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Test").project(testProject).build();

        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any()))
                .thenThrow(new IllegalStateException("Test instance down"));

        FactoryTestResult result = verificationService.deployAndVerify(workflow);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).contains("Test instance down");
    }

    @Test
    void deployAndVerify_failedRunWithStepErrors_extractsError() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Test").project(testProject)
                .defaultContext(new HashMap<>(Map.of("key", "value"))).build();

        var rootNode = objectMapper.createObjectNode();
        rootNode.put("status", "FAILED");
        var stepsArray = objectMapper.createArrayNode();
        var failedStep = objectMapper.createObjectNode();
        failedStep.put("status", "FAILED");
        failedStep.put("error", "Step error message");
        stepsArray.add(failedStep);
        rootNode.set("steps", stepsArray);

        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1")).thenReturn(rootNode);

        FactoryTestResult result = verificationService.deployAndVerify(workflow);

        assertThat(result.status()).isEqualTo("FAILED");
        assertThat(result.error()).isEqualTo("Step error message");
    }

    // ── verifyWorkflow ─────────────────────────────────

    @Test
    void verifyWorkflow_passed_returnsVerified() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Verify WF").project(testProject).build();
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        JsonNode completedRun = objectMapper.createObjectNode().put("status", "COMPLETED");
        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1")).thenReturn(completedRun);

        WorkflowFactoryResult result = verificationService.verifyWorkflow("wf-1");

        assertThat(result.status()).isEqualTo("VERIFIED");
        verify(testingService).reportTestResult("Verify WF", "Test Project", "PASSED");
    }

    // ── verifyAndFix ───────────────────────────────────

    @Test
    void verifyAndFix_passesOnFirstTry_noRetry() {
        Workflow workflow = Workflow.builder().id("wf-1").name("Test").project(testProject).build();
        when(workflowService.findById("wf-1")).thenReturn(workflow);

        JsonNode completedRun = objectMapper.createObjectNode().put("status", "COMPLETED");
        when(testingService.syncDefinitions()).thenReturn(null);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1")).thenReturn(completedRun);

        FactoryTestResult result = verificationService.verifyAndFix("wf-1", 3);

        assertThat(result.status()).isEqualTo("PASSED");
        verify(testingService).reportTestResult("Test", "Test Project", "PASSED");
    }

    // ── autoFixWorkflow (characterization, Audit-TODO 3b0a021c) ────

    private Workflow brokenWorkflow() {
        return Workflow.builder().id("wf-1").name("Broken").description("desc")
                .blocks(new java.util.ArrayList<>(List.of(BlockDefinition.builder().type("shell")
                        .config(new HashMap<>(Map.of("command", "exit 1"))).build())))
                .build();
    }

    @Test
    void autoFixWorkflow_arrayResponse_replacesBlocksAndUpdatesViaService() throws IOException {
        when(claudeGenerator.generate(anyString(), anyString(), eq(120)))
                .thenReturn("[{\"type\":\"shell\",\"config\":{\"command\":\"echo fixed\"}}]");
        Workflow workflow = brokenWorkflow();

        verificationService.autoFixWorkflow(workflow, "exit code 1");

        assertThat(workflow.getBlocks()).hasSize(1);
        assertThat(workflow.getBlocks().get(0).getConfig()).containsEntry("command", "echo fixed");
        verify(workflowService).update(eq("wf-1"), argThat(req ->
                req.blocks() != null && req.blocks().size() == 1
                        && "echo fixed".equals(req.blocks().get(0).getConfig().get("command"))
                        && req.name() == null && req.description() == null), eq(ChangeSource.FACTORY));
    }

    @Test
    void autoFixWorkflow_arrayResponse_keepsRequiredCapabilities() throws IOException {
        // TODO ba5df025: auto-fix replaces the block list wholesale, so capabilities must survive the round-trip.
        when(claudeGenerator.generate(anyString(), anyString(), eq(120)))
                .thenReturn("[{\"type\":\"shell\",\"config\":{\"command\":\"echo fixed\"},\"requiredCapabilities\":[\"docker\",\"web\"]}]");
        Workflow workflow = brokenWorkflow();

        verificationService.autoFixWorkflow(workflow, "exit code 1");

        assertThat(workflow.getBlocks().get(0).getRequiredCapabilities()).containsExactly("docker", "web");
        verify(workflowService).update(eq("wf-1"), argThat(req ->
                req.blocks() != null && req.blocks().size() == 1
                        && List.of("docker", "web").equals(req.blocks().get(0).getRequiredCapabilities())), eq(ChangeSource.FACTORY));
    }

    @Test
    void autoFixWorkflow_promptCarriesNameDescriptionBlocksAndError_withDebugSystemPrompt() throws IOException {
        when(claudeGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("[]");

        verificationService.autoFixWorkflow(brokenWorkflow(), "boom");

        verify(claudeGenerator).generate(
                argThat(p -> p.contains("WORKFLOW NAME: Broken") && p.contains("WORKFLOW DESCRIPTION: desc")
                        && p.contains("\"command\":\"exit 1\"") && p.contains("ERROR:\nboom")),
                eq("You are a workflow debugging assistant. Output only valid JSON."),
                eq(120));
    }

    @Test
    void autoFixWorkflow_unparseableResponse_leavesWorkflowUntouched() throws IOException {
        when(claudeGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("I cannot help with that.");
        Workflow workflow = brokenWorkflow();

        verificationService.autoFixWorkflow(workflow, "err");

        assertThat(workflow.getBlocks().get(0).getConfig()).containsEntry("command", "exit 1");
        verify(workflowService, never()).update(anyString(), any(), eq(ChangeSource.FACTORY));
    }

    @Test
    void autoFixWorkflow_providerOverride_usesCodexGenerator() throws IOException {
        when(codexGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("[]");

        verificationService.autoFixWorkflow(brokenWorkflow(), "err", "codex");

        verify(codexGenerator).generate(anyString(), anyString(), eq(120));
        verify(claudeGenerator, never()).generate(anyString(), anyString(), anyInt());
    }

    // ── build check after every repair (TODO 99a9dfb0) ────

    private Workflow namedWorkflow() {
        List<BlockDefinition> blocks = new java.util.ArrayList<>();
        blocks.add(BlockDefinition.builder().type("shell").name("fetch")
                .config(new HashMap<>(Map.of("command", "exit 1")))
                .skillIds(new java.util.ArrayList<>(List.of("skill-1"))).build());
        blocks.add(BlockDefinition.builder().type("simple").name("summarize")
                .config(new HashMap<>(Map.of("command", "Fasse zusammen: {{ block.fetch.output }}"))).build());
        return Workflow.builder().id("wf-1").name("Named").description("desc").project(testProject)
                .blocks(blocks).build();
    }

    @Test
    void autoFixWorkflow_repairLosesABlock_isDiscarded() throws IOException {
        // Typical failure: the model returns only the block it repaired.
        when(claudeGenerator.generate(anyString(), anyString(), eq(120)))
                .thenReturn("[{\"type\":\"shell\",\"name\":\"fetch\",\"config\":{\"command\":\"echo ok\"}}]");
        Workflow workflow = namedWorkflow();

        FactoryVerificationService.AutoFixOutcome outcome = verificationService.autoFixWorkflow(workflow, "exit code 1");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).contains("Blockzahl 2 → 1");
        assertThat(workflow.getBlocks()).hasSize(2);
        assertThat(workflow.getBlocks().get(0).getConfig()).containsEntry("command", "exit 1");
        verify(workflowService, never()).update(anyString(), any(), any());
    }

    @Test
    void autoFixWorkflow_repairChangesATopLevelType_isDiscarded() throws IOException {
        when(claudeGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("""
                [{"type":"agentic","name":"fetch","config":{"goal":"fetch"}},
                 {"type":"simple","name":"summarize","config":{"command":"Fasse zusammen: {{ block.fetch.output }}"}}]""");

        FactoryVerificationService.AutoFixOutcome outcome = verificationService.autoFixWorkflow(namedWorkflow(), "err");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).contains("Typ shell → agentic");
        verify(workflowService, never()).update(anyString(), any(), any());
    }

    @Test
    void autoFixWorkflow_repairWithANewHighFinding_isDiscarded() throws IOException {
        // Same shape, but the second block now points at a block that does not exist: unknown-block-reference (HIGH).
        when(claudeGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("""
                [{"type":"shell","name":"fetch","config":{"command":"echo ok"}},
                 {"type":"simple","name":"summarize","config":{"command":"Fasse zusammen: {{ block.gone.output }}"}}]""");
        Workflow workflow = namedWorkflow();

        FactoryVerificationService.AutoFixOutcome outcome = verificationService.autoFixWorkflow(workflow, "exit code 1");

        assertThat(outcome.applied()).isFalse();
        assertThat(outcome.reason()).contains("mehr HIGH-Befunde (0 → 1)");
        assertThat(workflow.getBlocks().get(0).getConfig()).containsEntry("command", "exit 1");
        verify(workflowService, never()).update(anyString(), any(), any());
    }

    @Test
    void autoFixWorkflow_goodRepair_isSaved_andKeepsOmittedNamesAndSkillIds() throws IOException {
        // The repair drops name and skillIds — they come back from the current version, so the reference still resolves.
        when(claudeGenerator.generate(anyString(), anyString(), eq(120))).thenReturn("""
                [{"type":"shell","config":{"command":"echo ok"}},
                 {"type":"simple","config":{"command":"Fasse zusammen: {{ block.fetch.output }}"}}]""");
        Workflow workflow = namedWorkflow();

        FactoryVerificationService.AutoFixOutcome outcome = verificationService.autoFixWorkflow(workflow, "exit code 1");

        assertThat(outcome.applied()).isTrue();
        verify(workflowService).update(eq("wf-1"), argThat(req -> req.blocks() != null
                && req.blocks().size() == 2
                && "echo ok".equals(req.blocks().get(0).getConfig().get("command"))
                && "fetch".equals(req.blocks().get(0).getName())
                && List.of("skill-1").equals(req.blocks().get(0).getSkillIds())
                && "summarize".equals(req.blocks().get(1).getName())), eq(ChangeSource.FACTORY));
    }

    @Test
    void verifyAndFix_discardedRepair_noExtraTestRunOnTheUnchangedWorkflow() {
        Workflow workflow = namedWorkflow();
        when(workflowService.findById("wf-1")).thenReturn(workflow);
        when(testingService.executeWorkflowOnTest(anyString(), anyString(), any())).thenReturn("run-1");
        when(testingService.pollTestRun("run-1"))
                .thenReturn(objectMapper.createObjectNode().put("status", "FAILED").put("error", "exit 1"));
        try {
            when(claudeGenerator.generate(anyString(), anyString(), eq(120)))
                    .thenReturn("[{\"type\":\"shell\",\"config\":{\"command\":\"echo ok\"}}]");
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }

        FactoryTestResult result = verificationService.verifyAndFix("wf-1", 2);

        assertThat(result.status()).isEqualTo("FAILED");
        verify(testingService, times(1)).executeWorkflowOnTest(anyString(), anyString(), any());
        verify(workflowService, never()).update(anyString(), any(), any());
        verify(testingService).reportTestResult("Named", "Test Project", "FAILED");
    }
}
