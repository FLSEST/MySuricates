package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.dto.request.CreateWorkflowRequest;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.chain.ChainService;
import com.automation.service.factory.FactoryTestResult;
import com.automation.service.factory.FactoryVerificationService;
import com.automation.service.workflow.WorkflowService;
import com.automation.service.workflow.analyzer.GeneratedWorkflowCheck;
import com.automation.service.workflow.analyzer.WorkflowAnalyzer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishDecomposeServiceTest {

    @Mock private WishRepository wishRepository;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowService workflowService;
    @Mock private ChainService chainService;
    @Mock private FactoryVerificationService factoryVerificationService;
    @Mock private WishPromptBuilder promptBuilder;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AiProvider aiProvider;
    @Mock private ActivityLogService activityLogService;

    private WishConfig wishConfig;
    private WishDecomposeService service;

    @BeforeEach
    void setUp() {
        wishConfig = new WishConfig();
        service = new WishDecomposeService(
                wishConfig, wishRepository, aiProviderRegistry, projectRepository,
                workflowService, chainService, factoryVerificationService,
                promptBuilder, messagingTemplate,
                new GeneratedWorkflowCheck(new WorkflowAnalyzer(), new ObjectMapper(), activityLogService)
        );
        lenient().when(aiProviderRegistry.getDefault()).thenReturn(aiProvider);
        lenient().when(promptBuilder.buildDecomposePrompt(any())).thenReturn("prompt");
        lenient().when(promptBuilder.buildDecomposeSystemPrompt(any())).thenReturn("system");
        lenient().when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Nested
    class DecomposeTests {

        @Test
        void singleWorkflow_createsWorkflowAndSetsApproval() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"analysis":"test","strategy":"simple","workflows":[
                      {"name":"My WF","description":"desc","blocks":[
                        {"type":"shell","config":{"command":"echo hello"}}
                      ]}
                    ],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            Workflow created = Workflow.builder().id("wf-1").name("My WF").build();
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(created);
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            verify(workflowService).create(any(), eq(ChangeSource.WISH));
            assertThat(wish.getGeneratedWorkflowIds()).contains("wf-1");
            assertThat(wish.getStatus()).isEqualTo(WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            assertThat(wish.getIterationCount()).isEqualTo(1);
        }

        @Test
        void blocksKeepNameSkillIdsAndCapabilities() throws Exception {
            // TODO d6be36f3: parseBlocks took only type and config — {{ block.fetch.output }} pointed nowhere.
            Wish wish = createWish("w-1");
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult("""
                    {"analysis":"test","strategy":"simple","workflows":[
                      {"name":"Report","blocks":[
                    {"type": "shell", "name": "fetch", "config": {"command": "cat data.txt"},
                     "skillIds": ["skill-1"], "requiredCapabilities": ["docker"]},
                    {"type": "simple", "name": "summarize", "config": {"command": "Fasse zusammen: {{ block.fetch.output }}"}}
                      ]}
                    ],"needsClarification":false}""", null));
            when(workflowService.create(any(), eq(ChangeSource.WISH)))
                    .thenReturn(Workflow.builder().id("wf-1").name("Report").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<CreateWorkflowRequest> captor = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            var blocks = captor.getValue().blocks();
            assertThat(blocks).extracting(com.automation.entity.BlockDefinition::getName).containsExactly("fetch", "summarize");
            assertThat(blocks.get(0).getSkillIds()).containsExactly("skill-1");
            assertThat(blocks.get(0).getRequiredCapabilities()).containsExactly("docker");
            // the reference resolves, so the build check needs no correction round
            verify(aiProvider, times(1)).reason(any());
            // default timeouts still apply to the parsed config
            assertThat(blocks.get(0).getConfig()).containsKey("timeout_seconds");
        }

        @Test
        void multipleWorkflows_createsChain() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"analysis":"test","strategy":"chain","workflows":[
                      {"name":"WF1","blocks":[{"type":"shell","config":{"command":"a"}}]},
                      {"name":"WF2","blocks":[{"type":"shell","config":{"command":"b"}}]}
                    ],"chain":{"name":"Test Chain","stopOnFailure":true},"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").build();
            Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").build();
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(wf1, wf2);
            WorkflowChain chain = WorkflowChain.builder().id("chain-1").build();
            when(chainService.create(any())).thenReturn(chain);
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            verify(workflowService, times(2)).create(any(), eq(ChangeSource.WISH));
            verify(chainService).create(any());
            assertThat(wish.getGeneratedChainId()).isEqualTo("chain-1");
            assertThat(wish.getGeneratedWorkflowIds()).hasSize(2);
        }

        @Test
        void autoRefine_skipsDecomposeApproval_andSetsExecuting() throws Exception {
            Wish wish = createWish("w-1");
            wish.setAutoRefine(true);
            String json = """
                    {"analysis":"test","strategy":"simple","workflows":[
                      {"name":"My WF","description":"desc","blocks":[
                        {"type":"shell","config":{"command":"echo hello"}}
                      ]}
                    ],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            Workflow created = Workflow.builder().id("wf-1").name("My WF").build();
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(created);
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            assertThat(wish.getStatus()).isEqualTo(WishStatus.EXECUTING);
            assertThat(wish.getIterationCount()).isEqualTo(1);
        }

        @Test
        void clarificationNeeded_setsAwaitingClarification() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"needsClarification":true,"clarificationQuestion":"Which database?"}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));

            service.decompose(wish);

            assertThat(wish.getStatus()).isEqualTo(WishStatus.AWAITING_CLARIFICATION);
            assertThat(wish.getMetadata()).containsEntry("clarificationQuestion", "Which database?");
            verify(workflowService, never()).create(any(), eq(ChangeSource.WISH));
        }

        @Test
        void jsonParseFailure_retriesWithShorterPrompt() throws Exception {
            Wish wish = createWish("w-1");
            // First call returns unparseable, second returns valid
            when(aiProvider.reason(any()))
                    .thenReturn(new AiProvider.ReasoningResult("not json {broken", null))
                    .thenReturn(new AiProvider.ReasoningResult(
                            "{\"workflows\":[{\"name\":\"WF\",\"blocks\":[]}],\"needsClarification\":false}", null));
            Workflow wf = Workflow.builder().id("wf-1").name("WF").build();
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(wf);
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            verify(aiProvider, times(2)).reason(any());
            assertThat(wish.getGeneratedWorkflowIds()).contains("wf-1");
        }

        @Test
        void iterationHistoryUpdated() throws Exception {
            Wish wish = createWish("w-1");
            String json = "{\"workflows\":[{\"name\":\"WF\",\"blocks\":[]}],\"needsClarification\":false}";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(Workflow.builder().id("wf-1").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            assertThat(wish.getIterationHistory()).hasSize(1);
            Map<String, Object> entry = wish.getIterationHistory().get(0);
            assertThat(entry).containsEntry("iteration", 1);
            assertThat(entry).containsEntry("phase", "decompose");
            assertThat(entry).containsKey("timestamp");
        }

        @Test
        void modelInferred_whenNullInResponse() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"workflows":[{"name":"Security Audit","description":"analyze vulnerabilities",
                    "blocks":[{"type":"agentic","config":{"goal":"audit code"}}]}],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            when(promptBuilder.inferModelFromBlocks(any(), eq("analyze vulnerabilities")))
                    .thenReturn("claude-opus-4-6");
            Workflow wf = Workflow.builder().id("wf-1").build();
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(wf);
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            assertThat(captor.getValue().model()).isEqualTo("claude-opus-4-6");
        }
    }

    @Nested
    class TimeoutDefaultsTests {

        @Test
        void shellBlock_getsDefaultTimeout() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"workflows":[{"name":"WF","blocks":[
                      {"type":"shell","config":{"command":"echo hi"}}
                    ]}],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(Workflow.builder().id("wf-1").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            BlockDefinition block = captor.getValue().blocks().get(0);
            assertThat(block.getConfig()).containsEntry("timeout_seconds", wishConfig.getDefaultShellTimeoutSeconds());
        }

        @Test
        void agenticBlock_getsAllThreeTimeouts() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"workflows":[{"name":"WF","blocks":[
                      {"type":"agentic","config":{"goal":"do stuff"}}
                    ]}],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(Workflow.builder().id("wf-1").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            Map<String, Object> config = captor.getValue().blocks().get(0).getConfig();
            assertThat(config).containsEntry("timeout_seconds", wishConfig.getDefaultAgenticTimeoutSeconds());
            assertThat(config).containsEntry("iteration_timeout_seconds", wishConfig.getDefaultAgenticIterationTimeoutSeconds());
            assertThat(config).containsEntry("total_timeout_seconds", wishConfig.getDefaultAgenticTotalTimeoutSeconds());
        }

        @Test
        void existingTimeout_notOverridden() throws Exception {
            Wish wish = createWish("w-1");
            String json = """
                    {"workflows":[{"name":"WF","blocks":[
                      {"type":"shell","config":{"command":"slow","timeout_seconds":1200}}
                    ]}],"needsClarification":false}""";
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult(json, null));
            when(workflowService.create(any(), eq(ChangeSource.WISH))).thenReturn(Workflow.builder().id("wf-1").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            assertThat(captor.getValue().blocks().get(0).getConfig())
                    .containsEntry("timeout_seconds", 1200);
        }
    }

    /** Build check after the decomposition (TODO 326d5987). */
    @Nested
    class BuildCheckTests {

        private static final String BAD = """
                {"workflows":[{"name":"Bericht","blocks":[
                  {"type":"simple","config":{"prompt":"Fasse zusammen"}}
                ]}],"needsClarification":false}""";

        private CreateWorkflowRequest savedRequest() {
            ArgumentCaptor<CreateWorkflowRequest> captor = ArgumentCaptor.forClass(CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            return captor.getValue();
        }

        @BeforeEach
        void stubs() {
            lenient().when(workflowService.create(any(), eq(ChangeSource.WISH)))
                    .thenReturn(Workflow.builder().id("wf-1").name("Bericht").build());
            lenient().when(factoryVerificationService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new FactoryTestResult("OK", null, null));
        }

        @Test
        void highFinding_exactlyOneCorrectionWithSameSystemPrompt_correctedVersionIsSaved() throws Exception {
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult(BAD, null),
                    new AiProvider.ReasoningResult("[{\"type\":\"simple\",\"config\":{\"command\":\"Fasse zusammen\"}}]", null));

            service.decompose(createWish("w-1"));

            ArgumentCaptor<AiProvider.ReasoningRequest> calls = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
            verify(aiProvider, times(2)).reason(calls.capture());
            AiProvider.ReasoningRequest correction = calls.getAllValues().get(1);
            assertThat(correction.prompt()).contains("HIGH missing-required-config-key");
            assertThat(correction.systemPrompt()).isEqualTo("system");
            assertThat(savedRequest().blocks()).singleElement().satisfies(b -> {
                assertThat(b.getConfig()).containsEntry("command", "Fasse zusammen");
                // the default timeouts apply to the corrected answer too
                assertThat(b.getConfig()).containsKey("timeout_seconds");
            });
            verifyNoInteractions(activityLogService);
        }

        @Test
        void unusableCorrection_originalIsSaved_andFindingsAreLogged() throws Exception {
            when(aiProvider.reason(any())).thenReturn(
                    new AiProvider.ReasoningResult(BAD, null),
                    new AiProvider.ReasoningResult("keine Ahnung", null));

            service.decompose(createWish("w-1"));

            verify(aiProvider, times(2)).reason(any());
            assertThat(savedRequest().blocks()).singleElement()
                    .satisfies(b -> assertThat(b.getConfig()).containsEntry("prompt", "Fasse zusammen"));
            verify(activityLogService).warn(any(), contains("Baufehler-Prüfung (Wunsch)"), anyString(), isNull(), eq("wf-1"));
        }

        @Test
        void noHighFinding_noExtraCall() throws Exception {
            when(aiProvider.reason(any())).thenReturn(new AiProvider.ReasoningResult("""
                    {"workflows":[{"name":"Bericht","blocks":[
                      {"type":"shell","config":{"command":"set -euo pipefail; echo ok"}}
                    ]}],"needsClarification":false}""", null));

            service.decompose(createWish("w-1"));

            verify(aiProvider, times(1)).reason(any());
        }
    }

    // --- Helpers ---

    private Wish createWish(String id) {
        return Wish.builder()
                .id(id)
                .originalText("Test wish")
                .status(WishStatus.DECOMPOSING)
                .metadata(new HashMap<>())
                .generatedWorkflowIds(new ArrayList<>())
                .referenceWorkflowIds(new ArrayList<>())
                .iterationHistory(new ArrayList<>())
                .iterationCount(0)
                .maxIterations(5)
                .build();
    }
}
