package com.automation.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantPushEventTest {

    // --- Accessor tests ---

    @Test
    void should_returnAllFields_when_constructedWithAllArgs() {
        var event = new AssistantPushEvent("push", "sess-123", "workflow_started", "Workflow gestartet",
                "Details zum Start", "/workflows/42", "2026-04-08T10:00:00Z");

        assertThat(event.type()).isEqualTo("push");
        assertThat(event.sessionId()).isEqualTo("sess-123");
        assertThat(event.eventKind()).isEqualTo("workflow_started");
        assertThat(event.title()).isEqualTo("Workflow gestartet");
        assertThat(event.detail()).isEqualTo("Details zum Start");
        assertThat(event.linkTo()).isEqualTo("/workflows/42");
        assertThat(event.timestamp()).isEqualTo("2026-04-08T10:00:00Z");
    }

    @Test
    void should_acceptNullDetail_when_constructed() {
        var event = new AssistantPushEvent("push", "sess-1", "info", "Title", null, "/link", "2026-04-08T10:00:00Z");

        assertThat(event.detail()).isNull();
        assertThat(event.type()).isEqualTo("push");
        assertThat(event.title()).isEqualTo("Title");
    }

    @Test
    void should_acceptNullLinkTo_when_constructed() {
        var event = new AssistantPushEvent("push", "sess-2", "alert", "Alert Title", "Some detail", null, "2026-04-08T10:00:00Z");

        assertThat(event.linkTo()).isNull();
        assertThat(event.eventKind()).isEqualTo("alert");
    }

    @Test
    void should_acceptNullTimestamp_when_constructed() {
        var event = new AssistantPushEvent("push", "sess-3", "done", "Finished", "All done", "/results", null);

        assertThat(event.timestamp()).isNull();
        assertThat(event.sessionId()).isEqualTo("sess-3");
    }

    @Test
    void should_acceptNullDetailLinkToAndTimestamp_when_allThreeNull() {
        var event = new AssistantPushEvent("push", "sess-4", "info", "Title", null, null, null);

        assertThat(event.detail()).isNull();
        assertThat(event.linkTo()).isNull();
        assertThat(event.timestamp()).isNull();
        assertThat(event.type()).isEqualTo("push");
        assertThat(event.sessionId()).isEqualTo("sess-4");
        assertThat(event.eventKind()).isEqualTo("info");
        assertThat(event.title()).isEqualTo("Title");
    }

    @Test
    void should_acceptAllFieldsAsNull_when_constructed() {
        var event = new AssistantPushEvent(null, null, null, null, null, null, null);

        assertThat(event.type()).isNull();
        assertThat(event.sessionId()).isNull();
        assertThat(event.eventKind()).isNull();
        assertThat(event.title()).isNull();
        assertThat(event.detail()).isNull();
        assertThat(event.linkTo()).isNull();
        assertThat(event.timestamp()).isNull();
    }

    @Test
    void should_handleEmptyStrings_when_constructed() {
        var event = new AssistantPushEvent("", "", "", "", "", "", "");

        assertThat(event.type()).isEmpty();
        assertThat(event.sessionId()).isEmpty();
        assertThat(event.eventKind()).isEmpty();
        assertThat(event.title()).isEmpty();
        assertThat(event.detail()).isEmpty();
        assertThat(event.linkTo()).isEmpty();
        assertThat(event.timestamp()).isEmpty();
    }

    // --- Equality / HashCode ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new AssistantPushEvent("push", "sess-1", "info", "Title", "Detail", "/link", "2026-04-08T10:00:00Z");
        var b = new AssistantPushEvent("push", "sess-1", "info", "Title", "Detail", "/link", "2026-04-08T10:00:00Z");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullOptionalFields() {
        var a = new AssistantPushEvent("push", "sess-1", "info", "Title", null, null, null);
        var b = new AssistantPushEvent("push", "sess-1", "info", "Title", null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentType() {
        var a = new AssistantPushEvent("push", "s", "k", "t", "d", "/l", "ts");
        var b = new AssistantPushEvent("event", "s", "k", "t", "d", "/l", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSessionId() {
        var a = new AssistantPushEvent("push", "sess-1", "k", "t", "d", "/l", "ts");
        var b = new AssistantPushEvent("push", "sess-2", "k", "t", "d", "/l", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentEventKind() {
        var a = new AssistantPushEvent("push", "s", "info", "t", "d", "/l", "ts");
        var b = new AssistantPushEvent("push", "s", "alert", "t", "d", "/l", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTitle() {
        var a = new AssistantPushEvent("push", "s", "k", "Title A", "d", "/l", "ts");
        var b = new AssistantPushEvent("push", "s", "k", "Title B", "d", "/l", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDetail() {
        var a = new AssistantPushEvent("push", "s", "k", "t", "detail-a", "/l", "ts");
        var b = new AssistantPushEvent("push", "s", "k", "t", "detail-b", "/l", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentLinkTo() {
        var a = new AssistantPushEvent("push", "s", "k", "t", "d", "/link-a", "ts");
        var b = new AssistantPushEvent("push", "s", "k", "t", "d", "/link-b", "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTimestamp() {
        var a = new AssistantPushEvent("push", "s", "k", "t", "d", "/l", "ts-1");
        var b = new AssistantPushEvent("push", "s", "k", "t", "d", "/l", "ts-2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqualToNull_when_compared() {
        var event = new AssistantPushEvent("push", "s", "k", "t", "d", "/l", "ts");

        assertThat(event).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqualToOtherType_when_compared() {
        var event = new AssistantPushEvent("push", "s", "k", "t", "d", "/l", "ts");

        assertThat(event).isNotEqualTo("a string");
    }

    // --- toString ---

    @Test
    void should_containAllFieldValues_when_toStringCalled() {
        var event = new AssistantPushEvent("push", "sess-42", "workflow_done", "Fertig",
                "Alles erledigt", "/results/99", "2026-04-08T12:00:00Z");
        var str = event.toString();

        assertThat(str).contains("push");
        assertThat(str).contains("sess-42");
        assertThat(str).contains("workflow_done");
        assertThat(str).contains("Fertig");
        assertThat(str).contains("Alles erledigt");
        assertThat(str).contains("/results/99");
        assertThat(str).contains("2026-04-08T12:00:00Z");
    }

    @Test
    void should_handleNullsInToString_when_optionalFieldsNull() {
        var event = new AssistantPushEvent("push", "sess-1", "info", "Title", null, null, null);
        var str = event.toString();

        assertThat(str).contains("null");
        assertThat(str).contains("push");
        assertThat(str).contains("info");
        assertThat(str).contains("Title");
    }
}
