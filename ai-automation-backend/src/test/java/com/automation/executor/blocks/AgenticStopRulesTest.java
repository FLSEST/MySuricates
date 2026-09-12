package com.automation.executor.blocks;

import com.automation.executor.ExecutionContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class AgenticStopRulesTest {

    @Mock private SimpMessagingTemplate messagingTemplate;
    private AgenticStopRules rules;
    private ExecutionContext context;

    @BeforeEach
    void setUp() {
        rules = new AgenticStopRules(new AgenticEventBroadcaster(messagingTemplate));
        context = ExecutionContext.builder().containerId("c").workflowRunId("run-1").build();
    }

    private static AgenticBlockConfig cfg(int maxTotalTokens, int maxRepeated) {
        return AgenticBlockConfig.from(java.util.Map.of("goal", "g", "max_total_tokens", maxTotalTokens,
                "max_repeated_actions", maxRepeated), ExecutionContext.builder().containerId("c").workflowRunId("r").build());
    }

    @Test
    void noRuleHit_returnsNull() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 3600, 3);
        String reason = rules.afterAct(context, 0, 1, Instant.now(), "ls", cfg(1_000_000, 3),
                new AgenticTokenTracker(), guard, new StringBuilder());
        assertThat(reason).isNull();
    }

    @Test
    void repeatedAction_stopsWithMessageInOutput() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 3600, 2);
        StringBuilder out = new StringBuilder();
        rules.afterAct(context, 0, 1, Instant.now(), "ls", cfg(1_000_000, 2), new AgenticTokenTracker(), guard, out);
        String reason = rules.afterAct(context, 0, 2, Instant.now(), "ls", cfg(1_000_000, 2), new AgenticTokenTracker(), guard, out);
        assertThat(reason).isNotNull();
        assertThat(out.toString()).contains("[ABORTED: Same action repeated 2 times]");
    }

    @Test
    void iterationTimeout_winsOverBudgetAndRepeat() {
        AgenticSafetyGuard guard = new AgenticSafetyGuard(3600, 1, 1);
        StringBuilder out = new StringBuilder();
        String reason = rules.afterAct(context, 0, 1, Instant.now().minusSeconds(5), "ls", cfg(1, 1),
                new AgenticTokenTracker(), guard, out);
        assertThat(out.toString()).contains("[iteration 1 - TIMEOUT]").doesNotContain("TOKEN BUDGET").doesNotContain("ABORTED");
        assertThat(reason).isNotNull();
    }
}
