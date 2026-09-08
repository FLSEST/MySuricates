package com.automation.service.assistant;

import com.automation.dto.AssistantChatRequest;
import com.automation.dto.AssistantChatRequest.PageContext;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantMutationGateTest {

    private static AssistantChatRequest request(String message, String page) {
        PageContext ctx = page == null ? null : new PageContext(page, null, null, null, null, null);
        return new AssistantChatRequest(message, List.of(), ctx, null, null, "s1", "r1");
    }

    @Test
    void hasExplicitMutationIntent_verbAndTarget_true() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Lege ein Todo für den Bug an", null))).isTrue();
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Starte den Workflow Daily Report", null))).isTrue();
    }

    @Test
    void hasExplicitMutationIntent_verbWithoutTarget_false() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Erstelle mir eine Zusammenfassung", null))).isFalse();
    }

    @Test
    void hasExplicitMutationIntent_targetWithoutVerb_false() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Wie geht es dem Workflow?", null))).isFalse();
    }

    @Test
    void hasExplicitMutationIntent_contextualRunVerbOnRunPage_true() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Nochmal bitte", "run"))).isTrue();
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("retry", "workflows"))).isTrue();
    }

    @Test
    void hasExplicitMutationIntent_contextualRunVerbOnOtherPage_false() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("Nochmal bitte", "todos"))).isFalse();
    }

    @Test
    void hasExplicitMutationIntent_blankOrNull_false() {
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(request("   ", "run"))).isFalse();
        assertThat(AssistantMutationGate.hasExplicitMutationIntent(null)).isFalse();
    }

    @Test
    void isMutatingActionType_onlyRunWorkflow() {
        assertThat(AssistantMutationGate.isMutatingActionType("run_workflow")).isTrue();
        assertThat(AssistantMutationGate.isMutatingActionType("show_workflow")).isFalse();
        assertThat(AssistantMutationGate.isMutatingActionType(null)).isFalse();
    }

    @Test
    void filterActions_nullOrEmpty_returnsEmpty() {
        assertThat(AssistantMutationGate.filterActions(null, true)).isEmpty();
        assertThat(AssistantMutationGate.filterActions(List.of(), true)).isEmpty();
    }
}
