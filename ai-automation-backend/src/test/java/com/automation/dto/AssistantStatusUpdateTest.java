package com.automation.dto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantStatusUpdateTest {

    // --- Accessor tests ---

    @Test
    void should_returnAllFields_when_constructedWithAllArgs() {
        var update = new AssistantStatusUpdate("status", "req-123", "THINKING", "Processing query", 1500L, "2026-04-08T10:00:00Z");

        assertThat(update.type()).isEqualTo("status");
        assertThat(update.requestId()).isEqualTo("req-123");
        assertThat(update.phase()).isEqualTo("THINKING");
        assertThat(update.detail()).isEqualTo("Processing query");
        assertThat(update.elapsedMs()).isEqualTo(1500L);
        assertThat(update.timestamp()).isEqualTo("2026-04-08T10:00:00Z");
    }

    @Test
    void should_acceptNullDetail_when_constructed() {
        var update = new AssistantStatusUpdate("status", "req-1", "ACTING", null, 0L, "2026-04-08T10:00:00Z");

        assertThat(update.detail()).isNull();
        assertThat(update.type()).isEqualTo("status");
        assertThat(update.phase()).isEqualTo("ACTING");
    }

    @Test
    void should_acceptNullTimestamp_when_constructed() {
        var update = new AssistantStatusUpdate("status", "req-2", "DONE", "Finished", 3000L, null);

        assertThat(update.timestamp()).isNull();
        assertThat(update.elapsedMs()).isEqualTo(3000L);
    }

    @Test
    void should_acceptNullDetailAndTimestamp_when_bothNull() {
        var update = new AssistantStatusUpdate("status", "req-3", "IDLE", null, 0L, null);

        assertThat(update.detail()).isNull();
        assertThat(update.timestamp()).isNull();
        assertThat(update.requestId()).isEqualTo("req-3");
    }

    @Test
    void should_acceptAllNullableFieldsAsNull_when_constructed() {
        var update = new AssistantStatusUpdate(null, null, null, null, 0L, null);

        assertThat(update.type()).isNull();
        assertThat(update.requestId()).isNull();
        assertThat(update.phase()).isNull();
        assertThat(update.detail()).isNull();
        assertThat(update.elapsedMs()).isZero();
        assertThat(update.timestamp()).isNull();
    }

    @Test
    void should_handleZeroElapsedMs_when_constructed() {
        var update = new AssistantStatusUpdate("status", "req-4", "START", "Begin", 0L, "2026-04-08T10:00:00Z");

        assertThat(update.elapsedMs()).isZero();
    }

    @Test
    void should_handleLargeElapsedMs_when_constructed() {
        var update = new AssistantStatusUpdate("status", "req-5", "LONG", "Running", Long.MAX_VALUE, "2026-04-08T10:00:00Z");

        assertThat(update.elapsedMs()).isEqualTo(Long.MAX_VALUE);
    }

    // --- Equality / HashCode ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new AssistantStatusUpdate("status", "req-1", "THINKING", "detail", 100L, "2026-04-08T10:00:00Z");
        var b = new AssistantStatusUpdate("status", "req-1", "THINKING", "detail", 100L, "2026-04-08T10:00:00Z");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveNullDetailAndTimestamp() {
        var a = new AssistantStatusUpdate("status", "req-1", "PHASE", null, 50L, null);
        var b = new AssistantStatusUpdate("status", "req-1", "PHASE", null, 50L, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentType() {
        var a = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts");
        var b = new AssistantStatusUpdate("error", "req-1", "P", "d", 0L, "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentRequestId() {
        var a = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts");
        var b = new AssistantStatusUpdate("status", "req-2", "P", "d", 0L, "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentPhase() {
        var a = new AssistantStatusUpdate("status", "req-1", "THINKING", "d", 0L, "ts");
        var b = new AssistantStatusUpdate("status", "req-1", "ACTING", "d", 0L, "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentElapsedMs() {
        var a = new AssistantStatusUpdate("status", "req-1", "P", "d", 100L, "ts");
        var b = new AssistantStatusUpdate("status", "req-1", "P", "d", 200L, "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDetail() {
        var a = new AssistantStatusUpdate("status", "req-1", "P", "detail-a", 0L, "ts");
        var b = new AssistantStatusUpdate("status", "req-1", "P", "detail-b", 0L, "ts");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTimestamp() {
        var a = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts-1");
        var b = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts-2");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqualToNull_when_compared() {
        var update = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts");

        assertThat(update).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqualToOtherType_when_compared() {
        var update = new AssistantStatusUpdate("status", "req-1", "P", "d", 0L, "ts");

        assertThat(update).isNotEqualTo("a string");
    }

    // --- toString ---

    @Test
    void should_containAllFieldValues_when_toStringCalled() {
        var update = new AssistantStatusUpdate("status", "req-42", "THINKING", "Some detail", 999L, "2026-04-08T12:00:00Z");
        var str = update.toString();

        assertThat(str).contains("status");
        assertThat(str).contains("req-42");
        assertThat(str).contains("THINKING");
        assertThat(str).contains("Some detail");
        assertThat(str).contains("999");
        assertThat(str).contains("2026-04-08T12:00:00Z");
    }

    @Test
    void should_handleNullsInToString_when_detailAndTimestampNull() {
        var update = new AssistantStatusUpdate("status", "req-1", "PHASE", null, 0L, null);
        var str = update.toString();

        assertThat(str).contains("null");
        assertThat(str).contains("status");
        assertThat(str).contains("PHASE");
    }
}
