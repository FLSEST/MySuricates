package com.automation.service.wish;

import com.automation.config.WishConfig;
import com.automation.entity.*;
import com.automation.repository.ChainRunRepository;
import com.automation.repository.WishRepository;
import com.automation.repository.WorkflowRunRepository;
import com.automation.service.chain.ChainExecutor;
import com.automation.service.chain.ChainService;
import com.automation.service.workflow.WorkflowExecutor;
import com.automation.service.workflow.WorkflowService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WishExecutionServiceTest {

    @Mock private WishRepository wishRepository;
    @Mock private ChainService chainService;
    @Mock private ChainExecutor chainExecutor;
    @Mock private WorkflowExecutor workflowExecutor;
    @Mock private WorkflowService workflowService;
    @Mock private WorkflowRunRepository workflowRunRepository;
    @Mock private ChainRunRepository chainRunRepository;
    @Mock private SimpMessagingTemplate messagingTemplate;

    private WishConfig wishConfig;
    private WishExecutionService service;

    @BeforeEach
    void setUp() {
        wishConfig = new WishConfig();
        // Use very short wait to keep tests fast
        wishConfig.setWorkflowWaitMinutes(0);
        service = new WishExecutionService(
                wishConfig, wishRepository, chainService, chainExecutor,
                workflowExecutor, workflowService,
                workflowRunRepository, chainRunRepository, messagingTemplate
        );
    }

    private final Supplier<Boolean> neverCancelled = () -> false;

    @Nested
    class ChainExecutionTests {

        @Test
        void chainExists_executesAndMovesToEvaluating() throws Exception {
            Wish wish = createWish("w-1", "chain-1", List.of());
            WorkflowChain chain = WorkflowChain.builder().id("chain-1").build();
            ChainRun chainRun = ChainRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(chainService.findById("chain-1")).thenReturn(chain);
            when(chainExecutor.execute(eq(chain), any())).thenReturn(chainRun);
            lenient().when(chainRunRepository.findById("run-1")).thenReturn(Optional.of(chainRun));
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            service.execute(wish, neverCancelled);

            assertThat(wish.getCurrentRunId()).isEqualTo("run-1");
            verify(chainExecutor).execute(eq(chain), any());
            // Final status should be EVALUATING
            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
        }
    }

    @Nested
    class WorkflowExecutionTests {

        @Test
        void singleWorkflow_executesAndMovesToEvaluating() throws Exception {
            Wish wish = createWish("w-1", null, List.of("wf-1"));
            Workflow workflow = Workflow.builder().id("wf-1").build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.COMPLETED).build();
            when(workflowService.findByIdEager("wf-1")).thenReturn(workflow);
            when(workflowExecutor.execute(eq(workflow), any())).thenReturn(run);
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            service.execute(wish, neverCancelled);

            assertThat(wish.getCurrentRunId()).isEqualTo("run-1");
            verify(workflowExecutor).execute(eq(workflow), any());
            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
        }
    }

    @Nested
    class NoWorkflowsTests {

        @Test
        void noWorkflows_setsFailedStatus() throws Exception {
            Wish wish = createWish("w-1", null, List.of());
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.execute(wish, neverCancelled);

            assertThat(wish.getStatus()).isEqualTo(WishStatus.FAILED);
            assertThat(wish.getError()).contains("No workflows");
            verify(workflowExecutor, never()).execute(any(), any());
        }
    }

    @Nested
    class CancellationTests {

        @Test
        void cancellationDuringWait_stillMovesToEvaluating() throws Exception {
            Wish wish = createWish("w-1", null, List.of("wf-1"));
            Workflow workflow = Workflow.builder().id("wf-1").build();
            WorkflowRun run = WorkflowRun.builder().id("run-1").status(RunStatus.RUNNING).build();
            when(workflowService.findByIdEager("wf-1")).thenReturn(workflow);
            when(workflowExecutor.execute(eq(workflow), any())).thenReturn(run);
            when(wishRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
            when(wishRepository.findById("w-1")).thenReturn(Optional.of(wish));

            // Cancel immediately
            service.execute(wish, () -> true);

            // Even with cancellation, moves to EVALUATING
            assertThat(wish.getStatus()).isEqualTo(WishStatus.EVALUATING);
        }
    }

    // --- Helpers ---

    private Wish createWish(String id, String chainId, List<String> workflowIds) {
        return Wish.builder()
                .id(id)
                .originalText("Test wish")
                .status(WishStatus.EXECUTING)
                .generatedChainId(chainId)
                .generatedWorkflowIds(new ArrayList<>(workflowIds))
                .metadata(new HashMap<>())
                .iterationHistory(new ArrayList<>(List.of(new HashMap<>(Map.of("phase", "execute")))))
                .iterationCount(1)
                .maxIterations(5)
                .build();
    }
}
