package com.automation.executor.blocks;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.entity.StepStatus;
import com.automation.executor.ExecutionContext;
import com.automation.executor.blocks.StepActors.Actor;
import com.automation.modules.ModuleRegistry;
import com.automation.service.cli.CommandExecutorService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The shared actor step of approval and human-input (java.design 1c23f026/a635c43d). */
class ActorDecisionTest {

    private final CommandExecutorService commandExecutor = mock(CommandExecutorService.class);
    private final StepActors actors = new StepActors(commandExecutor, ModuleRegistry.allEnabled());
    private final ExecutionContext context = ExecutionContext.builder().containerId("c").workflowRunId("run-1").build();
    private final BlockDefinition block = BlockDefinition.builder().type("approval").config(new HashMap<>()).build();

    private void aiSays(String text, StepStatus status) {
        StepResult call = new StepResult();
        call.setOutput(text);
        call.setStatus(status);
        call.setMetadata(new HashMap<>(Map.of("aiRequest", Map.of("provider", "CLAUDE"), "provider", "CLAUDE")));
        when(commandExecutor.executeStepOnHost(any())).thenReturn(call);
    }

    @Test
    @DisplayName("a person's step makes no AI call and records who answered")
    void person() {
        ActorDecision decision = actors.decide(Actor.HUMAN, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).isFalse();
        assertThat(decision.answeredByAi()).isFalse();
        assertThat(decision.shown("Deploy?")).isEqualTo("Deploy?");
        assertThat(decision.afterPerson(true)).containsExactly(Map.entry("answeredBy", "human"));
        assertThat(decision.unanswered()).isNull();
        verifyNoInteractions(commandExecutor);
    }

    @Test
    @DisplayName("the AI actor's text passes the check and becomes the answer")
    void aiAnswers() {
        aiSays(" ja ", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> text.strip().toUpperCase());

        assertThat(decision.answeredByAi()).isTrue();
        assertThat(decision.answer()).isEqualTo("JA");
        assertThat(decision.aiMetadata()).containsEntry("answeredBy", "ai").containsEntry("provider", "CLAUDE");
    }

    @Test
    @DisplayName("an answer the check rejects fails the step with the AI's text and the reason")
    void aiAnswerRejected() {
        aiSays("vielleicht", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> {
            throw new IllegalArgumentException("none of the options [Ja, Nein]");
        });

        assertThat(decision.failed()).isTrue();
        assertThat(decision.failure()).isEqualTo("AI actor answered 'vielleicht': none of the options [Ja, Nein]");
        assertThat(decision.answeredByAi()).isFalse();
    }

    @Test
    @DisplayName("a failed AI call fails an ai step and is never checked")
    void aiCallFails() {
        aiSays("Error: CLI exited 1", StepStatus.FAILED);

        ActorDecision decision = actors.decide(Actor.AI, block, context, 0, "task", text -> {
            throw new AssertionError("must not be checked");
        });

        assertThat(decision.failure()).isEqualTo("AI actor call failed: Error: CLI exited 1");
    }

    @Test
    @DisplayName("shadow: the proposal goes below the question, agreement only when a person answered")
    void shadow() {
        aiSays("Ja", StepStatus.COMPLETED);

        ActorDecision decision = actors.decide(Actor.SHADOW, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).isFalse();
        assertThat(decision.answeredByAi()).isFalse();
        assertThat(decision.shown("Deploy?")).startsWith("Deploy?").contains("**AI proposal (shadow):** Ja");
        assertThat(decision.afterPerson(false)).containsEntry("answeredBy", "shadow")
                .containsEntry("shadowAnswer", "Ja").containsEntry("agreed", false);
        assertThat(decision.unanswered()).containsEntry("shadowAnswer", "Ja").doesNotContainKey("agreed");
    }

    @Test
    @DisplayName("shadow with a failed call: empty proposal, still asks, never agrees")
    void shadowCallFails() {
        aiSays("Error: boom", StepStatus.FAILED);

        ActorDecision decision = actors.decide(Actor.SHADOW, block, context, 0, "task", text -> text);

        assertThat(decision.failed()).as("the person decides anyway").isFalse();
        assertThat(decision.proposalText()).isEmpty();
        assertThat(decision.afterPerson(true)).containsEntry("shadowCallFailed", true).doesNotContainKey("agreed");
    }

    @Test
    @DisplayName("with the module off, ai and shadow are refused and a person's step is not")
    void refusal() {
        StepActors off = new StepActors(commandExecutor, ModuleRegistry.of(Map.of(), Map.of()));

        assertThat(off.refusal(Actor.AI, "approval")).contains("actor: ai", "approval", "ai-actors");
        assertThat(off.refusal(Actor.SHADOW, "human-input")).contains("actor: shadow", "human-input");
        assertThat(off.refusal(Actor.HUMAN, "approval")).isNull();
        assertThat(actors.refusal(Actor.AI, "approval")).isNull();
    }
}
