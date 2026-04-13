package com.automation.dto;

import com.automation.dto.AssistantChatRequest.MessageEntry;
import com.automation.dto.AssistantChatRequest.PageContext;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantChatRequestTest {

    // --- Canonical constructor ---

    @Test
    void should_setAllFields_when_constructedWithAllArgs() {
        var history = List.of(new MessageEntry("user", "Hi"), new MessageEntry("assistant", "Hello"));
        var context = new PageContext("workflows", "e1", "p1", "w1", "r1", "/workflows/e1");
        var request = new AssistantChatRequest("Test message", history, context, "claude-sonnet-4-6", "sess-1", "req-1");

        assertThat(request.message()).isEqualTo("Test message");
        assertThat(request.history()).hasSize(2);
        assertThat(request.context()).isEqualTo(context);
        assertThat(request.provider()).isEqualTo("claude-sonnet-4-6");
        assertThat(request.sessionId()).isEqualTo("sess-1");
        assertThat(request.requestId()).isEqualTo("req-1");
    }

    @Test
    void should_acceptNulls_when_constructedWithAllNulls() {
        var request = new AssistantChatRequest(null, null, null, null, null, null);

        assertThat(request.message()).isNull();
        assertThat(request.history()).isNull();
        assertThat(request.context()).isNull();
        assertThat(request.provider()).isNull();
        assertThat(request.sessionId()).isNull();
        assertThat(request.requestId()).isNull();
    }

    @Test
    void should_acceptEmptyHistory_when_noMessages() {
        var request = new AssistantChatRequest("msg", List.of(), null, null, null, null);

        assertThat(request.message()).isEqualTo("msg");
        assertThat(request.history()).isEmpty();
    }

    // --- MessageEntry ---

    @Test
    void should_setRoleAndContent_when_messageEntryConstructed() {
        var entry = new MessageEntry("user", "Hello world");

        assertThat(entry.role()).isEqualTo("user");
        assertThat(entry.content()).isEqualTo("Hello world");
    }

    @Test
    void should_acceptNulls_when_messageEntryConstructedWithNulls() {
        var entry = new MessageEntry(null, null);

        assertThat(entry.role()).isNull();
        assertThat(entry.content()).isNull();
    }

    @Test
    void should_supportAssistantRole_when_messageEntryConstructed() {
        var entry = new MessageEntry("assistant", "I can help");

        assertThat(entry.role()).isEqualTo("assistant");
        assertThat(entry.content()).isEqualTo("I can help");
    }

    // --- PageContext: canonical (6-arg) constructor ---

    @Test
    void should_setAllContextFields_when_constructedWithSixArgs() {
        var ctx = new PageContext("workflows", "e1", "p1", "w1", "r1", "/workflows/e1");

        assertThat(ctx.page()).isEqualTo("workflows");
        assertThat(ctx.entityId()).isEqualTo("e1");
        assertThat(ctx.projectId()).isEqualTo("p1");
        assertThat(ctx.workflowId()).isEqualTo("w1");
        assertThat(ctx.runId()).isEqualTo("r1");
        assertThat(ctx.pathname()).isEqualTo("/workflows/e1");
    }

    @Test
    void should_acceptAllNulls_when_pageContextConstructedWithNulls() {
        var ctx = new PageContext(null, null, null, null, null, null);

        assertThat(ctx.page()).isNull();
        assertThat(ctx.entityId()).isNull();
        assertThat(ctx.projectId()).isNull();
        assertThat(ctx.workflowId()).isNull();
        assertThat(ctx.runId()).isNull();
        assertThat(ctx.pathname()).isNull();
    }

    // --- PageContext: convenience (3-arg) constructor ---

    @Test
    void should_setOnlyThreeFields_when_constructedWithThreeArgs() {
        var ctx = new PageContext("todos", "entity-id", "project-id");

        assertThat(ctx.page()).isEqualTo("todos");
        assertThat(ctx.entityId()).isEqualTo("entity-id");
        assertThat(ctx.projectId()).isEqualTo("project-id");
        assertThat(ctx.workflowId()).isNull();
        assertThat(ctx.runId()).isNull();
        assertThat(ctx.pathname()).isNull();
    }

    @Test
    void should_setRemainingFieldsToNull_when_threeArgConstructorUsed() {
        var ctx = new PageContext("dashboard", null, null);

        assertThat(ctx.page()).isEqualTo("dashboard");
        assertThat(ctx.entityId()).isNull();
        assertThat(ctx.projectId()).isNull();
        assertThat(ctx.workflowId()).isNull();
        assertThat(ctx.runId()).isNull();
        assertThat(ctx.pathname()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var history = List.of(new MessageEntry("user", "Hi"));
        var ctx = new PageContext("page", "e1", "p1");
        var a = new AssistantChatRequest("msg", history, ctx, "provider", "s1", "r1");
        var b = new AssistantChatRequest("msg", history, ctx, "provider", "s1", "r1");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentMessage() {
        var a = new AssistantChatRequest("msg1", null, null, null, null, null);
        var b = new AssistantChatRequest("msg2", null, null, null, null, null);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_messageEntriesHaveSameValues() {
        var a = new MessageEntry("user", "Hi");
        var b = new MessageEntry("user", "Hi");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_messageEntriesDiffer() {
        var a = new MessageEntry("user", "Hi");
        var b = new MessageEntry("assistant", "Hi");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_pageContextsSameValues() {
        var a = new PageContext("page", "e1", "p1", "w1", "r1", "/path");
        var b = new PageContext("page", "e1", "p1", "w1", "r1", "/path");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_pageContextsDiffer() {
        var a = new PageContext("page1", "e1", "p1");
        var b = new PageContext("page2", "e1", "p1");

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var request = new AssistantChatRequest("hello", List.of(), null, "prov", "s1", "r1");

        assertThat(request.toString()).contains("hello");
        assertThat(request.toString()).contains("prov");
        assertThat(request.toString()).contains("s1");
        assertThat(request.toString()).contains("r1");
    }

    @Test
    void should_containFieldValues_when_messageEntryToStringCalled() {
        var entry = new MessageEntry("user", "test content");

        assertThat(entry.toString()).contains("user");
        assertThat(entry.toString()).contains("test content");
    }

    @Test
    void should_containFieldValues_when_pageContextToStringCalled() {
        var ctx = new PageContext("workflows", "e1", "p1", "w1", "r1", "/path");

        assertThat(ctx.toString()).contains("workflows");
        assertThat(ctx.toString()).contains("e1");
        assertThat(ctx.toString()).contains("/path");
    }
}
