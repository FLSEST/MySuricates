package com.automation.executor.blocks;

import com.automation.executor.ApprovalRequest;
import com.automation.executor.ExecutionContext;
import com.automation.service.ai.ConversationPrompt.Turn;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AgenticApprovalGateTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    private AgenticApprovalGate gate;

    @BeforeEach
    void setUp() {
        gate = new AgenticApprovalGate(new AgenticEventBroadcaster(messagingTemplate));
    }

    private ExecutionContext contextAnswering(CompletableFuture<String> answer) {
        ExecutionContext context = spy(ExecutionContext.builder()
                .containerId("c").workflowRunId("run-1").build());
        doReturn(answer).when(context).requestApproval(any(ApprovalRequest.class));
        return context;
    }

    private AgenticPlan plan(String action) {
        return AgenticPlan.builder()
                .goalStatus("IN_PROGRESS")
                .rationale("because")
                .action(action)
                .summary("summary")
                .build();
    }

    @Test
    void no_returnsRetryIteration_andAppendsRejectionTurn() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("No"));
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();
        List<Turn> history = new ArrayList<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("rm -rf build"),
                iterData, fullOutput, history);

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.RETRY_ITERATION);
        assertThat(history).contains(new Turn("user", "The user rejected this action. Propose an alternative approach."));
        assertThat(fullOutput.toString()).contains("[iteration 1 - REJECTED]: rm -rf build");
        assertThat(iterData.get("approval")).isEqualTo("rejected");
    }

    /** On the event the run page polls GET /approval — the question must already be stored by then (5b08cf33). */
    @Test
    void registersTheQuestionBeforeAnnouncingIt() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));

        gate.ask(context, 2, 1, plan("do it"), new LinkedHashMap<>(), new StringBuilder(), new ArrayList<>());

        InOrder order = inOrder(context, messagingTemplate);
        order.verify(context).requestApproval(any(ApprovalRequest.class));
        order.verify(messagingTemplate).convertAndSend(anyString(), argThat((Object payload) ->
                payload instanceof Map<?, ?> map && "agentic_approval_required".equals(map.get("type"))));
    }

    @Test
    void yesAlways_returnsProceedAndDisable() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes always"));
        Map<String, Object> iterData = new LinkedHashMap<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, new StringBuilder(), new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.PROCEED_AND_DISABLE);
        assertThat(iterData.get("approval")).isEqualTo("approved");
    }

    @Test
    void yes_returnsProceed() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));
        Map<String, Object> iterData = new LinkedHashMap<>();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, new StringBuilder(), new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.PROCEED);
        assertThat(iterData.get("approval")).isEqualTo("approved");
    }

    @Test
    void futureTimesOut_returnsStop_andAppendsTimeoutLine() {
        // A future whose get(timeout, unit) always throws TimeoutException — exercises the gate's
        // timeout branch without waiting 30 minutes.
        CompletableFuture<String> neverCompletes = new CompletableFuture<>() {
            @Override
            public String get(long timeout, java.util.concurrent.TimeUnit unit)
                    throws java.util.concurrent.TimeoutException {
                throw new java.util.concurrent.TimeoutException("simulated");
            }
        };
        ExecutionContext context = contextAnswering(neverCompletes);
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, fullOutput, new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.STOP);
        assertThat(fullOutput.toString()).contains("[iteration 1 - APPROVAL TIMEOUT]");
        assertThat(iterData.get("result")).isEqualTo("approval_timeout");
    }

    @Test
    void futureFails_returnsStop_andAppendsApprovalError() {
        ExecutionContext context = contextAnswering(
                CompletableFuture.failedFuture(new IllegalStateException("channel closed")));
        Map<String, Object> iterData = new LinkedHashMap<>();
        StringBuilder fullOutput = new StringBuilder();

        AgenticApprovalGate.Decision decision = gate.ask(context, 0, 1, plan("do it"),
                iterData, fullOutput, new ArrayList<>());

        assertThat(decision).isEqualTo(AgenticApprovalGate.Decision.STOP);
        assertThat(fullOutput.toString()).contains("[iteration 1 - APPROVAL ERROR: ").contains("channel closed");
        assertThat(iterData.get("result")).isEqualTo("approval_error");
    }

    @Test
    void requestApproval_carriesQuestionAndOptionsAndBlockId() {
        ExecutionContext context = contextAnswering(CompletableFuture.completedFuture("Yes"));

        gate.ask(context, 3, 2, plan("rm -rf build"), new LinkedHashMap<>(), new StringBuilder(), new ArrayList<>());

        verify(context, times(1)).requestApproval(org.mockito.ArgumentMatchers.argThat(r ->
                r.getQuestion().equals("Approve action: rm -rf build")
                        && r.getOptions().equals(List.of("Yes", "Yes always", "No"))
                        && r.getBlockId().equals("agentic-3-iter-2")));
    }
}
