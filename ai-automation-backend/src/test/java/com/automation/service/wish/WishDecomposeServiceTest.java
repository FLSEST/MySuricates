package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.repository.ProjectRepository;
import com.automation.repository.WishRepository;
import com.automation.service.ai.AiProvider;
import com.automation.service.ai.AiProviderRegistry;
import com.automation.service.chain.ChainService;
import com.automation.service.factory.WorkflowFactoryService;
import com.automation.service.workflow.WorkflowService;
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
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishDecomposeServiceTest {

    @Mock private WishRepository wishRepository;
    @Mock private AiProviderRegistry aiProviderRegistry;
    @Mock private ProjectRepository projectRepository;
    @Mock private WorkflowService workflowService;
    @Mock private ChainService chainService;
    @Mock private WorkflowFactoryService workflowFactoryService;
    @Mock private WishPromptBuilder promptBuilder;
    @Mock private SimpMessagingTemplate messagingTemplate;
    @Mock private AiProvider aiProvider;

    private WishConfig wishConfig;
    private WishDecomposeService service;

    @BeforeEach
    void setUp() {
        wishConfig = new WishConfig();
        service = new WishDecomposeService(
                wishConfig, wishRepository, aiProviderRegistry, projectRepository,
                workflowService, chainService, workflowFactoryService,
                promptBuilder, messagingTemplate
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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

            service.decompose(wish);

            verify(workflowService).create(any(), eq(ChangeSource.WISH));
            assertThat(wish.getGeneratedWorkflowIds()).contains("wf-1");
            assertThat(wish.getStatus()).isEqualTo(WishStatus.AWAITING_DECOMPOSE_APPROVAL);
            assertThat(wish.getIterationCount()).isEqualTo(1);
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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

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
            lenient().when(workflowFactoryService.verifyAndFix(anyString(), anyInt()))
                    .thenReturn(new WorkflowFactoryService.FactoryTestResult("OK", null, null));

            service.decompose(wish);

            ArgumentCaptor<com.automation.dto.request.CreateWorkflowRequest> captor =
                    ArgumentCaptor.forClass(com.automation.dto.request.CreateWorkflowRequest.class);
            verify(workflowService).create(captor.capture(), eq(ChangeSource.WISH));
            assertThat(captor.getValue().blocks().get(0).getConfig())
                    .containsEntry("timeout_seconds", 1200);
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
