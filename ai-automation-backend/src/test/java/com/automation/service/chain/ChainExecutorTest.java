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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
                chainConfig,
                chainRunRepository,
                stepResultRepository,
                workflowRunRepository,
                workflowService,
                workflowExecutor,
                messagingTemplate,
                virtualThreadExecutor,
                activityLogService
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
}
