package com.automation.service.conversion;

import com.automation.dto.response.WorkflowResponse;
import com.automation.entity.*;
import com.automation.exception.ResourceNotFoundException;
import com.automation.mapper.WorkflowMapper;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.workflow.AgentInstructionsService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.automation.testsupport.FakeTransactions;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WorkflowConversionServiceTest {

    @Mock
    private WorkflowRunRepository runRepository;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowMapper workflowMapper;

    @Mock
    private AiProviderRegistry aiProviderRegistry;

    @Mock
    private AgentInstructionsService agentInstructionsService;

    @Mock
    private AiProvider aiProvider;

    @Mock
    private ActivityLogService activityLogService;

    private WorkflowConversionService conversionService;

    private GeneratedWorkflowCheck buildCheck() {
        return new GeneratedWorkflowCheck(new WorkflowAnalyzer(), new ObjectMapper(), activityLogService);
    }

    @BeforeEach
    void setUp() throws Exception {
        conversionService = new WorkflowConversionService(runRepository, workflowService, workflowMapper,
                aiProviderRegistry, agentInstructionsService, FakeTransactions.manager(), buildCheck());
        lenient().when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("");
        lenient().when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
    }

    private WorkflowRun buildCompletedRunWithAgenticSteps() {
        // Build iteration data that the rule-based converter expects
        List<Map<String, Object>> iterations = List.of(
                Map.of("action", "npm install", "exitCode", 0, "output", "installed"),
                Map.of("action", "npm test", "exitCode", 0, "output", "tests passed")
        );

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goalAchieved", true);
        metadata.put("goal", "Run project tests");
        metadata.put("iterations", iterations);

        StepResult agenticStep = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-1")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .startedAt(Instant.now())
                .completedAt(Instant.now())
                .steps(new ArrayList<>(List.of(agenticStep)))
                .build();
        agenticStep.setWorkflowRun(run);
        return run;
    }

    @Test
    void suggestWorkflow_aiBlocksKeepNameSkillIdsAndCapabilities() throws Exception {
        // TODO d6be36f3: the AI path took only type and config — {{ block.fetch.output }} pointed nowhere.
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult("""
                {"suggestedName": "Report", "blocks": [
                    {"type": "shell", "name": "fetch", "config": {"command": "cat data.txt"},
                     "skillIds": ["skill-1"], "requiredCapabilities": ["docker"]},
                    {"type": "simple", "name": "summarize", "config": {"command": "Fasse zusammen: {{ block.fetch.output }}"}}
                ]}
                """, null));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

        assertThat(suggestion.blocks()).extracting(com.automation.entity.BlockDefinition::getName).containsExactly("fetch", "summarize");
        assertThat(suggestion.blocks().get(0).getSkillIds()).containsExactly("skill-1");
        assertThat(suggestion.blocks().get(0).getRequiredCapabilities()).containsExactly("docker");
        // the reference resolves, so the build check needs no correction round
        verify(aiProvider, times(1)).reason(any());
    }

    @Test
    void suggestWorkflow_completedRunWithAgenticSteps_returnsSuggestion() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));

        // AI suggestion fails, so rule-based kicks in
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

        assertThat(suggestion.available()).isTrue();
        assertThat(suggestion.blocks()).hasSize(2);
        assertThat(suggestion.blocks().get(0).getType()).isEqualTo("simple");
        assertThat(suggestion.blocks().get(0).getConfig().get("command")).isEqualTo("npm install");
        assertThat(suggestion.blocks().get(1).getConfig().get("command")).isEqualTo("npm test");
        assertThat(suggestion.originalGoal()).isEqualTo("Run project tests");
        assertThat(suggestion.agenticStepsAnalyzed()).isEqualTo(2);
        assertThat(suggestion.suggestedName()).startsWith("Auto:");
    }

    @Test
    void suggestWorkflow_noAgenticSteps_returnsNotAvailable() {
        // Run with a non-agentic step
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "simple");

        StepResult simpleStep = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-2")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(simpleStep)))
                .build();

        when(runRepository.findById("run-2")).thenReturn(Optional.of(run));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-2");

        assertThat(suggestion.available()).isFalse();
        assertThat(suggestion.blocks()).isEmpty();
    }

    @Test
    void suggestWorkflow_failedRun_returnsNotAvailable() {
        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-3")
                .workflow(workflow)
                .status(RunStatus.FAILED)
                .steps(new ArrayList<>())
                .build();

        when(runRepository.findById("run-3")).thenReturn(Optional.of(run));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-3");

        assertThat(suggestion.available()).isFalse();
    }

    @Test
    void suggestWorkflow_runNotFound_throwsResourceNotFound() {
        when(runRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> conversionService.suggestWorkflow("unknown"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void suggestWorkflowRuleBased_extractsActionsAsSimpleBlocks() throws Exception {
        // Build run with multiple iterations, some with failures (exit != 0)
        List<Map<String, Object>> iterations = new ArrayList<>();
        iterations.add(new HashMap<>(Map.of("action", "echo hello", "exitCode", 0)));
        iterations.add(new HashMap<>(Map.of("action", "bad command", "exitCode", 1)));
        iterations.add(new HashMap<>(Map.of("action", "echo world", "exitCode", 0)));

        Map<String, Object> metadata = new HashMap<>();
        metadata.put("blockType", "agentic");
        metadata.put("goalAchieved", true);
        metadata.put("goal", "Print messages");
        metadata.put("iterations", iterations);

        StepResult step = StepResult.builder()
                .id("step-1")
                .status(StepStatus.COMPLETED)
                .metadata(metadata)
                .build();

        Workflow workflow = Workflow.builder().id("wf-1").build();
        WorkflowRun run = WorkflowRun.builder()
                .id("run-4")
                .workflow(workflow)
                .status(RunStatus.COMPLETED)
                .steps(new ArrayList<>(List.of(step)))
                .build();

        when(runRepository.findById("run-4")).thenReturn(Optional.of(run));
        // AI fails → rule-based fallback
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-4");

        assertThat(suggestion.available()).isTrue();
        // Only successful actions (exit 0) become blocks
        assertThat(suggestion.blocks()).hasSize(2);
        assertThat(suggestion.blocks().get(0).getConfig().get("command")).isEqualTo("echo hello");
        assertThat(suggestion.blocks().get(1).getConfig().get("command")).isEqualTo("echo world");
        // Each block has on_failure=stop
        assertThat(suggestion.blocks().get(0).getConfig().get("on_failure")).isEqualTo("stop");
    }

    @Test
    void createWorkflowFromSuggestion_createsWorkflowViaService() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));

        Workflow createdWorkflow = Workflow.builder()
                .id("new-wf")
                .name("My Workflow")
                .build();
        when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenReturn(createdWorkflow);

        WorkflowResponse expectedResponse = new WorkflowResponse(
                "new-wf", "proj-1", "Project", null, null, "My Workflow",
                null, null, List.of(), null, Map.of(), List.of(), List.of(), 0, null, null, null, null, Map.of(), null, null, null, null, null);
        when(workflowMapper.toResponse(createdWorkflow)).thenReturn(expectedResponse);

        WorkflowResponse result = conversionService.createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow");

        assertThat(result.id()).isEqualTo("new-wf");
        assertThat(result.name()).isEqualTo("My Workflow");

        ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
        verify(workflowService).create(captor.capture(), eq(ChangeSource.CONVERSION));

        var request = captor.getValue();
        assertThat(request.projectId()).isEqualTo("proj-1");
        assertThat(request.name()).isEqualTo("My Workflow");
        assertThat(request.blocks()).hasSize(2);
    }

    /**
     * TODO b07704ca: the prompt describes its block types from BlockSpecs. Its example recommended
     * retry without execution_mode, so a shell command in it ran as an AI prompt.
     */
    @Test
    void suggestWorkflow_promptCarriesTheBlockSpecificationsAndRetryRunsShellCommandsAsShell() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        ArgumentCaptor<AiProvider.ReasoningRequest> request = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        when(aiProvider.reason(request.capture())).thenThrow(new RuntimeException("no AI"));

        conversionService.suggestWorkflow("run-1");

        String prompt = request.getValue().prompt();
        assertThat(prompt).contains("Goal: Run project tests", "Step 2: npm test (exit: 0)");
        for (String type : List.of("simple", "shell", "retry", "conditional")) {
            assertThat(prompt).contains("**Block type: " + type + "**");
        }
        assertThat(prompt.lines().filter(line -> line.contains("\"type\": \"retry\"")).toList())
                .isNotEmpty().allMatch(line -> line.contains("\"execution_mode\": \"shell\""));
    }

    // ── transaction boundaries (HikariCP pool-size 1) ──────────────────────

    private WorkflowConversionService proxiedWithFakeTransactions() {
        PlatformTransactionManager transactions = FakeTransactions.manager();
        return FakeTransactions.proxy(new WorkflowConversionService(runRepository, workflowService,
                workflowMapper, aiProviderRegistry, agentInstructionsService, transactions, buildCheck()), transactions);
    }

    @Test
    void suggestWorkflow_readsTheRunInsideATransactionButAsksTheModelOutsideAny() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        AtomicReference<Boolean> txDuringRead = new AtomicReference<>();
        AtomicReference<Boolean> txDuringModelCall = new AtomicReference<>();
        when(runRepository.findById("run-1")).thenAnswer(inv -> {
            txDuringRead.set(FakeTransactions.active());
            return Optional.of(run);
        });
        when(aiProvider.reason(any())).thenAnswer(inv -> {
            txDuringModelCall.set(FakeTransactions.active());
            throw new RuntimeException("no AI");
        });

        WorkflowSuggestion suggestion = proxiedWithFakeTransactions().suggestWorkflow("run-1");

        assertThat(txDuringModelCall.get()).as("the model call must not hold the only DB connection").isFalse();
        assertThat(txDuringRead.get()).as("the run is read in a short transaction").isTrue();
        assertThat(suggestion.available()).isTrue();
        assertThat(suggestion.blocks()).hasSize(2);
    }

    @Test
    void createWorkflowFromSuggestion_asksTheModelOutsideAnyTransactionAndCreatesInsideOne() throws Exception {
        WorkflowRun run = buildCompletedRunWithAgenticSteps();
        when(runRepository.findById("run-1")).thenReturn(Optional.of(run));
        AtomicReference<Boolean> txDuringModelCall = new AtomicReference<>();
        AtomicReference<Boolean> txDuringCreate = new AtomicReference<>();
        AtomicReference<Boolean> txDuringMapping = new AtomicReference<>();
        when(aiProvider.reason(any())).thenAnswer(inv -> {
            txDuringModelCall.set(FakeTransactions.active());
            throw new RuntimeException("no AI");
        });
        Workflow created = Workflow.builder().id("new-wf").name("My Workflow").build();
        when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenAnswer(inv -> {
            txDuringCreate.set(FakeTransactions.active());
            return created;
        });
        WorkflowResponse response = mock(WorkflowResponse.class);
        when(workflowMapper.toResponse(created)).thenAnswer(inv -> {
            txDuringMapping.set(FakeTransactions.active());
            return response;
        });

        WorkflowResponse result = proxiedWithFakeTransactions()
                .createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow");

        assertThat(txDuringModelCall.get()).as("the model call must not hold the only DB connection").isFalse();
        assertThat(txDuringCreate.get()).as("the workflow is created in a transaction").isTrue();
        assertThat(txDuringMapping.get()).as("the response is mapped in the same transaction").isTrue();
        assertThat(result).isSameAs(response);
    }

    // ── Anlegen mit dem geprüften Vorschlag aus der Vorschau (TODO 935af6d3) ──

    @Nested
    class ReviewedSuggestion {

        private static WorkflowResponse response(String id, String name) {
            return new WorkflowResponse(id, "proj-1", "Project", null, null, name,
                    null, null, List.of(), null, Map.of(), List.of(), List.of(), 0, null, null, null, null, Map.of(),
                    null, null, null, null, null);
        }

        private List<BlockDefinition> reviewed() {
            return List.of(
                    BlockDefinition.builder().type("shell").config(Map.of("command", "set -euo pipefail; npm test")).build(),
                    // Ein Baufehler (simple ohne command): Die Meldung nach dem Speichern bleibt.
                    BlockDefinition.builder().type("simple").config(Map.of("prompt", "Fasse zusammen")).build());
        }

        @Test
        void withReviewedBlocks_savesExactlyThose_withoutAskingTheModel() throws Exception {
            when(runRepository.findById("run-1")).thenReturn(Optional.of(buildCompletedRunWithAgenticSteps()));
            Workflow created = Workflow.builder().id("new-wf").name("Geprüft").build();
            when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenReturn(created);
            when(workflowMapper.toResponse(created)).thenReturn(response("new-wf", "Geprüft"));
            List<BlockDefinition> blocks = reviewed();

            WorkflowResponse result = conversionService.createWorkflowFromSuggestion(
                    "run-1", "proj-1", "Geprüft", "Aus der Vorschau", blocks);

            assertThat(result.id()).isEqualTo("new-wf");
            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.CONVERSION));
            assertThat(captor.getValue().projectId()).isEqualTo("proj-1");
            assertThat(captor.getValue().name()).isEqualTo("Geprüft");
            assertThat(captor.getValue().description()).isEqualTo("Aus der Vorschau");
            assertThat(captor.getValue().blocks()).containsExactlyElementsOf(blocks);
            // Kein neuer Vorschlag: kein KI-Aufruf, kein Provider.
            verifyNoInteractions(aiProvider, aiProviderRegistry);
            // Die deterministische Baufehler-Meldung nach dem Speichern bleibt.
            verify(activityLogService).warn(any(), contains("Baufehler-Prüfung (Konvertierung)"), anyString(),
                    isNull(), eq("new-wf"));
        }

        @Test
        void withReviewedBlocks_checksTheRunInATransaction_andCreatesInAnother() {
            AtomicReference<Boolean> txDuringRead = new AtomicReference<>();
            AtomicReference<Boolean> txDuringCreate = new AtomicReference<>();
            when(runRepository.findById("run-1")).thenAnswer(inv -> {
                txDuringRead.set(FakeTransactions.active());
                return Optional.of(buildCompletedRunWithAgenticSteps());
            });
            Workflow created = Workflow.builder().id("new-wf").name("Geprüft").build();
            when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenAnswer(inv -> {
                txDuringCreate.set(FakeTransactions.active());
                return created;
            });
            when(workflowMapper.toResponse(created)).thenReturn(response("new-wf", "Geprüft"));

            proxiedWithFakeTransactions().createWorkflowFromSuggestion("run-1", "proj-1", "Geprüft", null, reviewed());

            assertThat(txDuringRead.get()).isTrue();
            assertThat(txDuringCreate.get()).isTrue();
        }

        /** Gleiches Fehlerbild wie suggestWorkflow: unbekannter Lauf → 404. */
        @Test
        void withReviewedBlocks_unknownRun_throwsNotFound_andSavesNothing() {
            when(runRepository.findById("unknown")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> conversionService.createWorkflowFromSuggestion(
                    "unknown", "proj-1", "Geprüft", null, reviewed()))
                    .isInstanceOf(ResourceNotFoundException.class);
            verifyNoInteractions(workflowService, aiProvider);
        }

        /** Gleiches Fehlerbild wie der alte Weg: ein nicht abgeschlossener Lauf hat keinen Vorschlag. */
        @Test
        void withReviewedBlocks_runNotCompleted_throwsNoConversionAvailable() {
            WorkflowRun running = buildCompletedRunWithAgenticSteps();
            running.setStatus(RunStatus.RUNNING);
            when(runRepository.findById("run-1")).thenReturn(Optional.of(running));

            assertThatThrownBy(() -> conversionService.createWorkflowFromSuggestion(
                    "run-1", "proj-1", "Geprüft", null, reviewed()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No conversion available for run run-1");
            verifyNoInteractions(workflowService, aiProvider);
        }

        /** Mit Blöcken gibt es keinen Server-Vorschlag, aus dem der Name käme: Der Client schickt ihn mit. */
        @Test
        void withReviewedBlocks_withoutName_isRejected_beforeAnything() {
            assertThatThrownBy(() -> conversionService.createWorkflowFromSuggestion(
                    "run-1", "proj-1", " ", null, reviewed()))
                    .isInstanceOf(IllegalArgumentException.class);
            verifyNoInteractions(runRepository, workflowService, aiProvider);
        }

        /** Ohne (oder mit leeren) Blöcken bleibt es beim alten Weg: Der Vorschlag wird neu erzeugt. */
        @Test
        void withoutReviewedBlocks_theSuggestionIsCreatedAgain() throws Exception {
            when(runRepository.findById("run-1")).thenReturn(Optional.of(buildCompletedRunWithAgenticSteps()));
            when(aiProvider.reason(any())).thenThrow(new RuntimeException("no AI"));
            Workflow created = Workflow.builder().id("new-wf").name("My Workflow").build();
            when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenReturn(created);
            when(workflowMapper.toResponse(created)).thenReturn(response("new-wf", "My Workflow"));

            conversionService.createWorkflowFromSuggestion("run-1", "proj-1", "My Workflow", "ignoriert", List.of());

            verify(aiProvider).reason(any());
            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.CONVERSION));
            // Der regelbasierte Vorschlag (npm install, npm test), nicht die leere Liste.
            assertThat(captor.getValue().blocks()).hasSize(2);
            assertThat(captor.getValue().description()).startsWith("Automatically generated");
        }
    }

    // ── build check after the AI suggestion (TODO 326d5987) ──

    @Nested
    class BuildCheck {

        private static final String BAD = """
                {"suggestedName": "Bericht", "blocks": [{"type": "simple", "config": {"prompt": "Fasse zusammen"}}]}
                """;

        @BeforeEach
        void run() {
            lenient().when(agentInstructionsService.buildInstructionPrefix(null)).thenReturn("system");
            when(runRepository.findById("run-1")).thenReturn(Optional.of(buildCompletedRunWithAgenticSteps()));
        }

        @Test
        void highFinding_exactlyOneCorrectionWithSameSystemPrompt_correctedVersionIsSuggested() throws Exception {
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult(BAD, null),
                    new AiProvider.ReasoningResult("[{\"type\":\"simple\",\"config\":{\"command\":\"Fasse zusammen\"}}]", null));

            WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

            ArgumentCaptor<AiProvider.ReasoningRequest> calls = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider, times(2)).reason(calls.capture());
            assertThat(calls.getAllValues().get(1).prompt()).contains("HIGH missing-required-config-key");
            assertThat(calls.getAllValues().get(1).systemPrompt()).isEqualTo("system");
            assertThat(suggestion.blocks()).singleElement()
                    .satisfies(b -> assertThat(b.getConfig()).containsEntry("command", "Fasse zusammen"));
            assertThat(suggestion.buildFindings()).isEmpty();
        }

        @Test
        void unusableCorrection_originalIsSuggested_withTheRemainingFindings() throws Exception {
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult(BAD, null), new AiProvider.ReasoningResult("nein", null));

            WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

            verify(aiProvider, times(2)).reason(any());
            assertThat(suggestion.blocks()).singleElement()
                    .satisfies(b -> assertThat(b.getConfig()).containsEntry("prompt", "Fasse zusammen"));
            assertThat(suggestion.buildFindings()).first().asString().startsWith("HIGH missing-required-config-key");
        }

        @Test
        void noHighFinding_noExtraCall() throws Exception {
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult("""
                    {"blocks": [{"type": "shell", "config": {"command": "set -euo pipefail; echo ok"}}]}
                    """, null));

            WorkflowSuggestion suggestion = conversionService.suggestWorkflow("run-1");

            verify(aiProvider, times(1)).reason(any());
            assertThat(suggestion.buildFindings()).isEmpty();
        }

        @Test
        void createWorkflowFromSuggestion_logsRemainingFindingsAfterTheTransaction() throws Exception {
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult(BAD, null), new AiProvider.ReasoningResult("nein", null));
            Workflow created = Workflow.builder().id("new-wf").name("Bericht").build();
            when(workflowService.create(any(), eq(ChangeSource.CONVERSION))).thenReturn(created);
            when(workflowMapper.toResponse(created)).thenReturn(new WorkflowResponse(
                    "new-wf", "proj-1", "Project", null, null, "Bericht",
                    null, null, List.of(), null, Map.of(), List.of(), List.of(), 0, null, null, null, null, Map.of(),
                    null, null, null, null, null));

            conversionService.createWorkflowFromSuggestion("run-1", "proj-1", null);

            verify(activityLogService).warn(any(), contains("Baufehler-Prüfung (Konvertierung)"), anyString(),
                    isNull(), eq("new-wf"));
        }
    }
}
