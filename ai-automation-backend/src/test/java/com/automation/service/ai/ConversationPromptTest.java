package com.automation.service.ai;

import com.automation.service.ai.ConversationPrompt.Turn;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationPromptTest {

    private static final List<Turn> HISTORY = List.of(new Turn("user", "Hello"), new Turn("assistant", "Hi there"));

    @Test
    void build_withoutHistory_onlyAsksForTheAnswer() {
        assertThat(ConversationPrompt.build(List.of())).isEqualTo("""
                === YOUR RESPONSE ===
                Respond following the format specified in the system instructions.""");
    }

    @Test
    void build_labelsEveryTurn() {
        assertThat(ConversationPrompt.build(HISTORY)).isEqualTo("""
                === CONVERSATION HISTORY ===
                [USER]
                Hello

                [ASSISTANT]
                Hi there

                === YOUR RESPONSE ===
                Respond following the format specified in the system instructions.""");
    }

    @Test
    void withInstructions_putsTheSystemPromptFirst() {
        assertThat(ConversationPrompt.withInstructions("Be brief.", HISTORY)).isEqualTo("""
                === INSTRUCTIONS ===
                Be brief.

                === CONVERSATION HISTORY ===
                [USER]
                Hello

                [ASSISTANT]
                Hi there

                === YOUR RESPONSE ===
                Respond following the format specified in the instructions above.""");
    }

    @Test
    void withInstructions_withoutSystemPrompt_skipsTheInstructions() {
        assertThat(ConversationPrompt.withInstructions(" ", List.of()))
                .startsWith("=== YOUR RESPONSE ===")
                .doesNotContain("INSTRUCTIONS ===");
    }

    @Test
    void anUnknownRoleCountsAsUser() {
        assertThat(ConversationPrompt.build(List.of(new Turn("tool", "output")))).contains("[USER]\noutput");
    }
}
