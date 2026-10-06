package com.automation.executor.blocks;

import com.automation.service.ai.AiProvider;
import com.automation.service.ai.ConversationPrompt.Turn;
import com.automation.service.learning.LearningService;
import com.automation.service.system.SystemIdentityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Locks prompt/context construction of the agentic loop before extraction (Audit-TODO a253ad3e). */
@ExtendWith(MockitoExtension.class)
class AgenticPromptBuilderTest {

    @Mock private LearningService learningService;
    @Mock private SystemIdentityService systemIdentity;
    @Mock private AiProvider provider;

    private AgenticPromptBuilder builder;

    @BeforeEach
    void setUp() {
        builder = new AgenticPromptBuilder(learningService, systemIdentity);
    }

    @Test
    void buildSystemPrompt_embedsIdentityGoalCriteriaAndLearning_noInstructionsSectionWhenBlank() {
        when(systemIdentity.getVersionedName()).thenReturn("MySuricates v1");

        String p = builder.buildSystemPrompt("Fix the build", "Tests green", "\n## Previously Tried Strategies\n- x\n", "  ");

        assertThat(p).startsWith("You are the reasoning engine of MySuricates v1, an AI workflow automation platform.");
        assertThat(p).contains("## Your Goal\nFix the build\n\n## Success Criteria\nTests green\n## Response Format");
        assertThat(p).doesNotContain("## Workflow Instructions");
        assertThat(p).endsWith("- After each observation, assess progress towards the goal.\n\n## Previously Tried Strategies\n- x\n");
    }

    @Test
    void buildSystemPrompt_withInstructions_insertsSectionBeforeResponseFormat() {
        when(systemIdentity.getVersionedName()).thenReturn("MySuricates v1");

        String p = builder.buildSystemPrompt("G", "C", "", "Never touch prod.");

        assertThat(p).contains("## Success Criteria\nC\n\n## Workflow Instructions\n"
                + "The following instructions apply to this workflow and should guide your planning:\nNever touch prod.\n## Response Format");
    }

    @Test
    void buildLearningContext_nullWorkflowId_empty_noLookup() {
        assertThat(builder.buildLearningContext(null)).isEmpty();
        verifyNoInteractions(learningService);
    }

    @Test
    void buildLearningContext_noHistory_empty() {
        when(learningService.getAgenticStrategyHistory("wf")).thenReturn(List.of());

        assertThat(builder.buildLearningContext("wf")).isEmpty();
    }

    @Test
    void buildLearningContext_formatsEntries_andOptionalStrategy() {
        Map<String, Object> withStrategy = new HashMap<>(Map.of("goal", "Deploy", "totalIterations", 3, "outcome", "achieved", "strategy", "retry twice"));
        Map<String, Object> noStrategy = new HashMap<>(Map.of("goal", "Lint", "totalIterations", 1, "outcome", "failed", "strategy", ""));
        when(learningService.getAgenticStrategyHistory("wf")).thenReturn(List.of(withStrategy, noStrategy));

        String ctx = builder.buildLearningContext("wf");

        assertThat(ctx).isEqualTo("\n## Previously Tried Strategies (from past runs)\n"
                + "- Goal: Deploy | Iterations: 3 | Outcome: achieved\n  Strategies: retry twice\n"
                + "- Goal: Lint | Iterations: 1 | Outcome: failed\n");
    }

    @Test
    void buildLearningContext_serviceThrows_empty() {
        when(learningService.getAgenticStrategyHistory("wf")).thenThrow(new RuntimeException("db"));

        assertThat(builder.buildLearningContext("wf")).isEmpty();
    }

    @Test
    void compressHistory_sevenOrFewerTurns_unchanged() {
        List<Turn> history = new ArrayList<>();
        for (int i = 0; i < 7; i++) history.add(new Turn(i % 2 == 0 ? "user" : "assistant", "t" + i));

        builder.compressHistory(history);

        assertThat(history).hasSize(7);
        assertThat(history.get(0).content()).isEqualTo("t0");
    }

    @Test
    void compressHistory_eightTurns_keepsFirstAndLastFour_collapsesMiddle() {
        List<Turn> history = new ArrayList<>();
        for (int i = 0; i < 8; i++) history.add(new Turn(i % 2 == 0 ? "user" : "assistant", "t" + i));

        builder.compressHistory(history);

        assertThat(history).hasSize(6);
        assertThat(history.get(0).content()).isEqualTo("t0");
        assertThat(history.get(1).role()).isEqualTo("user");
        assertThat(history.get(1).content()).isEqualTo("[Compressed history of 3 turns]\n- ASSISTANT: t1\n- USER: t2\n- ASSISTANT: t3\n");
        assertThat(history.subList(2, 6)).extracting(Turn::content).containsExactly("t4", "t5", "t6", "t7");
    }

    @Test
    void compressAction_returnsProviderText_withPromptPrefix() throws Exception {
        when(provider.reason(any())).thenReturn(new AiProvider.ReasoningResult("short action", null));

        String result = builder.compressAction("very long action", provider, "model-x");

        assertThat(result).isEqualTo("short action");
        ArgumentCaptor<AiProvider.ReasoningRequest> req = ArgumentCaptor.forClass(AiProvider.ReasoningRequest.class);
        verify(provider).reason(req.capture());
        assertThat(req.getValue().prompt()).startsWith("You are a task compression assistant.").endsWith("=== OVERSIZED ACTION ===\nvery long action");
        assertThat(req.getValue().model()).isEqualTo("model-x");
    }

    @Test
    void compressAction_blankResult_throwsIOException() throws Exception {
        when(provider.reason(any())).thenReturn(new AiProvider.ReasoningResult("  ", null));

        assertThatThrownBy(() -> builder.compressAction("x", provider, null))
                .isInstanceOf(IOException.class)
                .hasMessage("Action compression returned empty result");
    }

    @Test
    void truncate_nullIsEmpty_cutAppendsEllipsis_shortUnchanged() {
        assertThat(AgenticPromptBuilder.truncate(null, 5)).isEmpty();
        assertThat(AgenticPromptBuilder.truncate("abcdef", 3)).isEqualTo("abc...");
        assertThat(AgenticPromptBuilder.truncate("abc", 3)).isEqualTo("abc");
    }
}
