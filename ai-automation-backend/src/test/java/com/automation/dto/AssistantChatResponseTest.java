package com.automation.dto;

import com.automation.dto.AssistantChatResponse.AssistantAction;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantChatResponseTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- 2-arg constructor ---

    @Test
    void should_setMessageAndActions_when_constructedWithTwoArgs() {
        var actions = List.of(new AssistantAction("run_workflow", "w1", "My WF", "Run"));
        var response = new AssistantChatResponse("Hello", actions);

        assertThat(response.message()).isEqualTo("Hello");
        assertThat(response.actions()).isEqualTo(actions);
        assertThat(response.model()).isNull();
        assertThat(response.responseTimeMs()).isNull();
    }

    @Test
    void should_acceptNullMessage_when_constructedWithTwoArgs() {
        var response = new AssistantChatResponse(null, List.of());

        assertThat(response.message()).isNull();
        assertThat(response.actions()).isEmpty();
    }

    @Test
    void should_acceptNullActions_when_constructedWithTwoArgs() {
        var response = new AssistantChatResponse("msg", null);

        assertThat(response.message()).isEqualTo("msg");
        assertThat(response.actions()).isNull();
    }

    // --- 3-arg constructor ---

    @Test
    void should_setMessageActionsAndModel_when_constructedWithThreeArgs() {
        var actions = List.of(new AssistantAction("show_run", "r1", "Run #1", "Show"));
        var response = new AssistantChatResponse("Hi", actions, "claude-opus-4-6");

        assertThat(response.message()).isEqualTo("Hi");
        assertThat(response.actions()).isEqualTo(actions);
        assertThat(response.model()).isEqualTo("claude-opus-4-6");
        assertThat(response.responseTimeMs()).isNull();
    }

    @Test
    void should_acceptNullModel_when_constructedWithThreeArgs() {
        var response = new AssistantChatResponse("msg", List.of(), null);

        assertThat(response.model()).isNull();
        assertThat(response.responseTimeMs()).isNull();
    }

    // --- 4-arg constructor (model + provider) ---

    @Test
    void should_setModelAndProvider_when_constructedWithFourArgs() {
        var actions = List.of(new AssistantAction("start_wish", null, "wish text", "Start"));
        var response = new AssistantChatResponse("Full", actions, "claude-sonnet-4-6", "CLAUDE");

        assertThat(response.message()).isEqualTo("Full");
        assertThat(response.actions()).isEqualTo(actions);
        assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.provider()).isEqualTo("CLAUDE");
        assertThat(response.responseTimeMs()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructedWithFourArgs() {
        var response = new AssistantChatResponse(null, null, null, (String) null);

        assertThat(response.message()).isNull();
        assertThat(response.actions()).isNull();
        assertThat(response.model()).isNull();
        assertThat(response.provider()).isNull();
        assertThat(response.responseTimeMs()).isNull();
    }

    // --- 5-arg (canonical) constructor ---

    @Test
    void should_setAllFields_when_constructedWithFiveArgs() {
        var actions = List.of(new AssistantAction("start_wish", null, "wish text", "Start"));
        var response = new AssistantChatResponse("Full", actions, "claude-sonnet-4-6", "CODEX", 1234L);

        assertThat(response.message()).isEqualTo("Full");
        assertThat(response.actions()).isEqualTo(actions);
        assertThat(response.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(response.provider()).isEqualTo("CODEX");
        assertThat(response.responseTimeMs()).isEqualTo(1234L);
    }

    // --- withResponseTimeMs ---

    @Test
    void should_returnNewInstanceWithResponseTime_when_withResponseTimeMsCalled() {
        var original = new AssistantChatResponse("msg", List.of(), "model");

        var updated = original.withResponseTimeMs(500L);

        assertThat(updated).isNotSameAs(original);
        assertThat(updated.message()).isEqualTo("msg");
        assertThat(updated.actions()).isEmpty();
        assertThat(updated.model()).isEqualTo("model");
        assertThat(updated.responseTimeMs()).isEqualTo(500L);
    }

    @Test
    void should_preserveOriginalResponseTimeNull_when_withResponseTimeMsCalled() {
        var original = new AssistantChatResponse("msg", List.of());

        assertThat(original.responseTimeMs()).isNull();

        var updated = original.withResponseTimeMs(999L);

        // original remains unchanged
        assertThat(original.responseTimeMs()).isNull();
        assertThat(updated.responseTimeMs()).isEqualTo(999L);
    }

    @Test
    void should_overrideExistingResponseTime_when_withResponseTimeMsCalledAgain() {
        var first = new AssistantChatResponse("msg", List.of(), "m", "CLAUDE", 100L);
        var second = first.withResponseTimeMs(200L);

        assertThat(first.responseTimeMs()).isEqualTo(100L);
        assertThat(second.responseTimeMs()).isEqualTo(200L);
    }

    // --- AssistantAction: 5-arg (canonical) constructor ---

    @Test
    void should_setAllActionFields_when_constructedWithFiveArgs() {
        var options = List.of("Option A", "Option B");
        var action = new AssistantAction("ask_choice", null, "Choose", "Pick one", options);

        assertThat(action.type()).isEqualTo("ask_choice");
        assertThat(action.id()).isNull();
        assertThat(action.name()).isEqualTo("Choose");
        assertThat(action.label()).isEqualTo("Pick one");
        assertThat(action.options()).containsExactly("Option A", "Option B");
    }

    // --- AssistantAction: 4-arg constructor ---

    @Test
    void should_setOptionsToNull_when_constructedWithFourArgs() {
        var action = new AssistantAction("run_workflow", "w1", "My Workflow", "Run it");

        assertThat(action.type()).isEqualTo("run_workflow");
        assertThat(action.id()).isEqualTo("w1");
        assertThat(action.name()).isEqualTo("My Workflow");
        assertThat(action.label()).isEqualTo("Run it");
        assertThat(action.options()).isNull();
    }

    @Test
    void should_acceptAllNullsInAction_when_constructedWithFourArgs() {
        var action = new AssistantAction(null, null, null, null);

        assertThat(action.type()).isNull();
        assertThat(action.id()).isNull();
        assertThat(action.name()).isNull();
        assertThat(action.label()).isNull();
        assertThat(action.options()).isNull();
    }

    // --- AssistantAction: various types ---

    @Test
    void should_supportAllActionTypes_when_constructed() {
        var types = List.of("run_workflow", "show_workflow", "start_wish", "show_run", "ask_choice");

        for (String type : types) {
            var action = new AssistantAction(type, "id-1", "name", "label");
            assertThat(action.type()).isEqualTo(type);
        }
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var actions = List.of(new AssistantAction("run_workflow", "w1", "WF", "Run"));
        var a = new AssistantChatResponse("msg", actions, "model", "CLAUDE", 100L);
        var b = new AssistantChatResponse("msg", actions, "model", "CLAUDE", 100L);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentResponseTime() {
        var a = new AssistantChatResponse("msg", List.of(), "model", "CLAUDE", 100L);
        var b = new AssistantChatResponse("msg", List.of(), "model", "CLAUDE", 200L);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_actionsHaveSameValues() {
        var a = new AssistantAction("run_workflow", "w1", "WF", "Run", List.of("opt"));
        var b = new AssistantAction("run_workflow", "w1", "WF", "Run", List.of("opt"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_actionsDifferInOptions() {
        var a = new AssistantAction("ask_choice", null, "name", "label", List.of("A"));
        var b = new AssistantAction("ask_choice", null, "name", "label", List.of("B"));

        assertThat(a).isNotEqualTo(b);
    }

    // --- JSON serialization (@JsonInclude NON_NULL) ---

    @Test
    void should_omitResponseTimeMs_when_null() throws Exception {
        var response = new AssistantChatResponse("msg", List.of(), "model");
        var json = objectMapper.writeValueAsString(response);

        assertThat(json).doesNotContain("responseTimeMs");
        assertThat(json).contains("\"message\":\"msg\"");
        assertThat(json).contains("\"model\":\"model\"");
    }

    @Test
    void should_includeResponseTimeMs_when_present() throws Exception {
        var response = new AssistantChatResponse("msg", List.of(), "model", "CLAUDE", 42L);
        var json = objectMapper.writeValueAsString(response);

        assertThat(json).contains("\"responseTimeMs\":42");
    }

    @Test
    void should_omitOptions_when_nullInAction() throws Exception {
        var action = new AssistantAction("run_workflow", "w1", "WF", "Run");
        var json = objectMapper.writeValueAsString(action);

        assertThat(json).doesNotContain("options");
        assertThat(json).contains("\"type\":\"run_workflow\"");
    }

    @Test
    void should_includeOptions_when_presentInAction() throws Exception {
        var action = new AssistantAction("ask_choice", null, "Choose", "Pick", List.of("A", "B"));
        var json = objectMapper.writeValueAsString(action);

        assertThat(json).contains("\"options\":[\"A\",\"B\"]");
    }

    @Test
    void should_includeEmptyOptions_when_emptyListInAction() throws Exception {
        var action = new AssistantAction("ask_choice", null, "Choose", "Pick", List.of());
        var json = objectMapper.writeValueAsString(action);

        assertThat(json).contains("\"options\":[]");
    }

    @Test
    void should_deserializeResponse_when_validJson() throws Exception {
        var original = new AssistantChatResponse("hello", List.of(
                new AssistantAction("run_workflow", "w1", "WF", "Run")
        ), "claude-sonnet-4-6", "CLAUDE", 123L);

        var json = objectMapper.writeValueAsString(original);
        var deserialized = objectMapper.readValue(json, AssistantChatResponse.class);

        assertThat(deserialized).isEqualTo(original);
    }

    @Test
    void should_deserializeAction_when_optionsPresent() throws Exception {
        var original = new AssistantAction("ask_choice", null, "Q", "Pick", List.of("X", "Y"));

        var json = objectMapper.writeValueAsString(original);
        var deserialized = objectMapper.readValue(json, AssistantAction.class);

        assertThat(deserialized).isEqualTo(original);
    }
}
