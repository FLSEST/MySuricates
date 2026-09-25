package com.automation.service.chain;

import com.automation.config.ChainConfig;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.StepResultRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.activitylog.ActivityLogService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChainExecutorTest {

    @Mock
    private ChainRunRepository chainRunRepository;

    @Mock
    private StepResultRepository stepResultRepository;

    @Mock
    private WorkflowRunRepository workflowRunRepository;

    @Mock
    private WorkflowService workflowService;

    @Mock
    private WorkflowExecutor workflowExecutor;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private ExecutorService virtualThreadExecutor;

    @Mock
    private ActivityLogService activityLogService;

    private ChainExecutor chainExecutor;

    private WorkflowChain testChain;

    @BeforeEach
    void setUp() {
        ChainConfig chainConfig = new ChainConfig();
        chainConfig.setWorkflowTimeoutMinutes(180);

        chainExecutor = new ChainExecutor(
                chainRunRepository,
                workflowRunRepository,
                workflowService,
                workflowExecutor,
                messagingTemplate,
                virtualThreadExecutor,
                activityLogService,
                new ChainRunWaiter(chainConfig, workflowRunRepository, stepResultRepository)
        );

        testChain = WorkflowChain.builder()
                .id("chain-1")
                .name("Test Chain")
                .stopOnFailure(true)
                .entries(new ArrayList<>(List.of(
                        ChainEntry.builder()
                                .workflowId("wf-1")
                                .label("First Workflow")
                                .sortOrder(0)
                                .stopOnFailure(true)
                                .build(),
                        ChainEntry.builder()
                                .workflowId("wf-2")
                                .label("Second Workflow")
                                .sortOrder(1)
                                .stopOnFailure(true)
                                .build()
                )))
                .build();

        lenient().when(chainRunRepository.save(any(ChainRun.class))).thenAnswer(invocation -> {
            ChainRun r = invocation.getArgument(0);
            if (r.getId() == null) r.setId("run-1");
            return r;
        });
    }

    @Test
    void execute_withValidChain_createsRunAndSubmitsTask() {
        doAnswer(inv -> {
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        Map<String, Object> inputContext = Map.of("key", "value");
        ChainRun result = chainExecutor.execute(testChain, inputContext);

        verify(chainRunRepository).save(any(ChainRun.class));
        assertThat(result.getStatus()).isEqualTo(RunStatus.RUNNING);
        verify(virtualThreadExecutor).submit(any(Runnable.class));
    }

    @Test
    void execute_withNullContext_usesEmptyMap() {
        doAnswer(inv -> {
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        ChainRun result = chainExecutor.execute(testChain, null);

        assertThat(result.getInputContext()).isNotNull();
        assertThat(result.getInputContext()).isEmpty();
    }

    @Test
    void execute_createsRunWithCorrectFields() {
        doAnswer(inv -> {
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        Map<String, Object> inputContext = new HashMap<>(Map.of("env", "test"));
        ChainRun result = chainExecutor.execute(testChain, inputContext);

        ArgumentCaptor<ChainRun> captor = ArgumentCaptor.forClass(ChainRun.class);
        verify(chainRunRepository).save(captor.capture());
        ChainRun saved = captor.getValue();

        assertThat(saved.getChain()).isEqualTo(testChain);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(saved.getStartedAt()).isNotNull();
        assertThat(saved.getInputContext()).containsEntry("env", "test");
        assertThat(saved.getEntryRuns()).isNotNull().isEmpty();
    }

    @Test
    void cancel_withActiveRun_setsCancellationFlag() {
        doAnswer(inv -> {
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        ChainRun run = chainExecutor.execute(testChain, null);

        // cancel should not throw and should set the flag
        chainExecutor.cancel(run.getId());

        // Verify the run was created and cancel was invoked without error
        assertThat(run.getId()).isEqualTo("run-1");
    }

    @Test
    void cancel_withUnknownRunId_doesNotThrow() {
        // Calling cancel with a run ID that was never registered should not throw
        chainExecutor.cancel("non-existent-run-id");
    }

    // --- Tests for executeEntries (run synchronously via inline executor) ---

    @Test
    void executeEntries_allSuccess_setsCompleted() {
        setupSynchronousExecutor();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();

        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);

        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);

        WorkflowRun completedRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED).build();
        WorkflowRun completedRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(completedRun1));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(completedRun2));

        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-1"))
                .thenReturn(List.of(StepResult.builder().output("output-1").stepIndex(0).build()));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-2"))
                .thenReturn(List.of(StepResult.builder().output("output-2").stepIndex(0).build()));

        chainExecutor.execute(testChain, Map.of("key", "val"));

        ChainRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getEntryRuns()).hasSize(2);
        assertThat(saved.getEntryRuns().get(0).getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(saved.getEntryRuns().get(1).getStatus()).isEqualTo(RunStatus.COMPLETED);
    }

    @Test
    void executeEntries_failureWithStopOnFailure_stopsChain() {
        setupSynchronousExecutor();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);

        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);

        WorkflowRun failedRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.FAILED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(failedRun));

        chainExecutor.execute(testChain, null);

        ChainRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        // Only 1 entry run — second workflow was never started
        assertThat(saved.getEntryRuns()).hasSize(1);
    }

    @Test
    void executeEntries_failureWithoutStopOnFailure_continues() {
        setupSynchronousExecutor();

        // Chain and entries both have stopOnFailure=false
        WorkflowChain noStopChain = WorkflowChain.builder()
                .id("chain-2").name("No-Stop Chain").stopOnFailure(false)
                .entries(new ArrayList<>(List.of(
                        ChainEntry.builder().workflowId("wf-1").sortOrder(0).stopOnFailure(false).build(),
                        ChainEntry.builder().workflowId("wf-2").sortOrder(1).stopOnFailure(false).build()
                )))
                .build();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);

        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);

        WorkflowRun failedRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.FAILED).build();
        WorkflowRun completedRun = WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(failedRun));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(completedRun));

        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-2"))
                .thenReturn(List.of(StepResult.builder().output("ok").stepIndex(0).build()));

        chainExecutor.execute(noStopChain, null);

        ChainRun saved = captureLastSavedRun();
        // Both entries ran; chain ends COMPLETED_WITH_ERRORS because of the failure
        assertThat(saved.getEntryRuns()).hasSize(2);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void executeEntries_cancellation_setsCancelled() {
        // Execute synchronously but set cancellation flag before submission
        lenient().doAnswer(inv -> {
            // Set cancel flag before running
            chainExecutor.cancel("run-1");
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        when(chainRunRepository.findById("run-1")).thenReturn(Optional.of(
                ChainRun.builder().id("run-1").status(RunStatus.RUNNING)
                        .chain(testChain).entryRuns(new ArrayList<>()).build()));

        chainExecutor.execute(testChain, null);

        ChainRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.CANCELLED);
        // No entries should have been started
        assertThat(saved.getEntryRuns()).isEmpty();
    }

    @Test
    void executeEntries_workflowNotFound_handlesGracefully() {
        setupSynchronousExecutor();

        when(workflowService.findByIdEager("wf-1"))
                .thenThrow(new RuntimeException("Workflow not found"));

        chainExecutor.execute(testChain, null);

        ChainRun saved = captureLastSavedRun();
        // stopOnFailure=true on both chain and entry → chain should fail
        assertThat(saved.getStatus()).isEqualTo(RunStatus.FAILED);
        assertThat(saved.getError()).contains("Workflow not found");
    }

    @Test
    void executeEntries_contextPropagation_previousChainOutput() {
        setupSynchronousExecutor();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);

        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);

        WorkflowRun completedRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED).build();
        WorkflowRun completedRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(completedRun1));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(completedRun2));

        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-1"))
                .thenReturn(List.of(StepResult.builder().output("first-output").stepIndex(0).build()));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-2"))
                .thenReturn(List.of(StepResult.builder().output("second-output").stepIndex(0).build()));

        chainExecutor.execute(testChain, Map.of("env", "test"));

        // Capture the context passed to second workflow
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor, times(2)).execute(any(Workflow.class), contextCaptor.capture(), eq(TriggerSource.CHAIN));

        // First entry: no prior output → placeholder must resolve to "" (not leak literally)
        Map<String, Object> firstContext = contextCaptor.getAllValues().get(0);
        assertThat(firstContext).containsEntry("previous_chain_output", "");
        assertThat(firstContext).containsEntry("env", "test");

        Map<String, Object> secondContext = contextCaptor.getAllValues().get(1);
        assertThat(secondContext).containsEntry("previous_chain_output", "first-output");
        assertThat(secondContext).containsEntry("env", "test");
        assertThat(secondContext).containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL");
    }

    @Test
    void entryRunsCarryTheChainOriginAndIgnoreASpoofedInput() {
        setupSynchronousExecutor();
        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);
        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED).build()));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build()));

        chainExecutor.execute(testChain, Map.of(TriggerSource.PARENT_CONTEXT_KEY, "MANUAL"), "schedule-1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor, times(2)).execute(any(Workflow.class), contextCaptor.capture(), eq(TriggerSource.CHAIN));
        assertThat(contextCaptor.getAllValues())
                .allSatisfy(ctx -> assertThat(ctx).containsEntry(TriggerSource.PARENT_CONTEXT_KEY, "SCHEDULE"));
    }

    /**
     * Every entry run gets its chain run as {@code {{ chain_run_id }}} — the key WorkflowExecutor
     * records on the run row of a CHAIN run. Neither the chain's input nor an entry's context can
     * point it at another chain run (TODO a3b89355).
     */
    @Test
    void entryRunsCarryTheChainRunIdAndIgnoreASpoofedInput() {
        setupSynchronousExecutor();
        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();
        testChain.getEntries().get(1).setInputContext(new HashMap<>(Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, "entry-spoof")));
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);
        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED).build()));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build()));

        ChainRun chainRun = chainExecutor.execute(testChain, Map.of(TriggerSource.CHAIN_RUN_CONTEXT_KEY, "input-spoof"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> contextCaptor = ArgumentCaptor.forClass(Map.class);
        verify(workflowExecutor, times(2)).execute(any(Workflow.class), contextCaptor.capture(), eq(TriggerSource.CHAIN));
        assertThat(chainRun.getId()).isEqualTo("run-1");
        assertThat(contextCaptor.getAllValues())
                .allSatisfy(ctx -> assertThat(ctx).containsEntry(TriggerSource.CHAIN_RUN_CONTEXT_KEY, "run-1"));
    }

    @Test
    void executeEntries_broadcastsStatus() {
        setupSynchronousExecutor();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);

        // Only one entry for simplicity
        testChain.setEntries(new ArrayList<>(List.of(
                ChainEntry.builder().workflowId("wf-1").sortOrder(0).stopOnFailure(true).build()
        )));

        WorkflowRun wfRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun);

        WorkflowRun completedRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(completedRun));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-1")).thenReturn(List.of());

        chainExecutor.execute(testChain, null);

        // WebSocket broadcast should have been called multiple times
        verify(messagingTemplate, atLeast(2)).convertAndSend(eq("/topic/chains/run-1/status"), any(String.class));
    }

    @Test
    void execute_withScheduleTriggerId_storesIt() {
        doReturn(null).when(virtualThreadExecutor).submit(any(Runnable.class));

        ChainRun result = chainExecutor.execute(testChain, null, "schedule-42");

        assertThat(result.getTriggeredByScheduleId()).isEqualTo("schedule-42");
    }

    @Test
    void executeEntries_completedWithErrors_setsCorrectStatus() {
        setupSynchronousExecutor();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);

        testChain.setEntries(new ArrayList<>(List.of(
                ChainEntry.builder().workflowId("wf-1").sortOrder(0).stopOnFailure(true).build()
        )));

        WorkflowRun wfRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun);

        WorkflowRun warningRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.COMPLETED_WITH_ERRORS).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(warningRun));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-1"))
                .thenReturn(List.of(StepResult.builder().output("warning-output").stepIndex(0).build()));

        chainExecutor.execute(testChain, null);

        ChainRun saved = captureLastSavedRun();
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
        assertThat(saved.getEntryRuns().get(0).getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    @Test
    void shouldStop_requiresBothChainAndEntryFlags() {
        setupSynchronousExecutor();

        // Chain stopOnFailure=true, but entry stopOnFailure=false
        WorkflowChain mixedChain = WorkflowChain.builder()
                .id("chain-3").name("Mixed Chain").stopOnFailure(true)
                .entries(new ArrayList<>(List.of(
                        ChainEntry.builder().workflowId("wf-1").sortOrder(0).stopOnFailure(false).build(),
                        ChainEntry.builder().workflowId("wf-2").sortOrder(1).stopOnFailure(true).build()
                )))
                .build();

        Project project = Project.builder().id("proj-1").name("Test Project").build();
        Workflow wf1 = Workflow.builder().id("wf-1").name("WF1").project(project).build();
        Workflow wf2 = Workflow.builder().id("wf-2").name("WF2").project(project).build();
        when(workflowService.findByIdEager("wf-1")).thenReturn(wf1);
        when(workflowService.findByIdEager("wf-2")).thenReturn(wf2);

        WorkflowRun wfRun1 = WorkflowRun.builder().id("wfr-1").status(RunStatus.RUNNING).build();
        WorkflowRun wfRun2 = WorkflowRun.builder().id("wfr-2").status(RunStatus.RUNNING).build();
        when(workflowExecutor.execute(eq(wf1), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun1);
        when(workflowExecutor.execute(eq(wf2), any(), eq(TriggerSource.CHAIN))).thenReturn(wfRun2);

        // First fails, second succeeds
        WorkflowRun failedRun = WorkflowRun.builder().id("wfr-1").status(RunStatus.FAILED).build();
        WorkflowRun completedRun = WorkflowRun.builder().id("wfr-2").status(RunStatus.COMPLETED).build();
        when(workflowRunRepository.findById("wfr-1")).thenReturn(Optional.of(failedRun));
        when(workflowRunRepository.findById("wfr-2")).thenReturn(Optional.of(completedRun));
        when(stepResultRepository.findByWorkflowRunIdOrderByStepIndexAsc("wfr-2"))
                .thenReturn(List.of(StepResult.builder().output("ok").stepIndex(0).build()));

        chainExecutor.execute(mixedChain, null);

        ChainRun saved = captureLastSavedRun();
        // Entry 1 has stopOnFailure=false → chain continues despite failure
        assertThat(saved.getEntryRuns()).hasSize(2);
        assertThat(saved.getStatus()).isEqualTo(RunStatus.COMPLETED_WITH_ERRORS);
    }

    // --- Helpers ---

    private void setupSynchronousExecutor() {
        lenient().doAnswer(inv -> {
            ((Runnable) inv.getArgument(0)).run();
            return null;
        }).when(virtualThreadExecutor).submit(any(Runnable.class));

        lenient().when(chainRunRepository.findById("run-1")).thenReturn(Optional.of(
                ChainRun.builder().id("run-1").status(RunStatus.RUNNING)
                        .chain(testChain).entryRuns(new ArrayList<>()).build()));
    }

    private ChainRun captureLastSavedRun() {
        ArgumentCaptor<ChainRun> captor = ArgumentCaptor.forClass(ChainRun.class);
        verify(chainRunRepository, atLeastOnce()).save(captor.capture());
        List<ChainRun> saved = captor.getAllValues();
        return saved.get(saved.size() - 1);
    }
}
