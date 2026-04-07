package com.automation.event;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSessionUpdatedEventTest {

    // --- Record accessor ---

    @Test
    void should_returnPage_when_constructed() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("dashboard");

        assertThat(event.page()).isEqualTo("dashboard");
    }

    @Test
    void should_returnNull_when_pageIsNull() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent(null);

        assertThat(event.page()).isNull();
    }

    @Test
    void should_returnEmptyString_when_pageIsEmpty() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("");

        assertThat(event.page()).isEmpty();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_samePageValue() {
        AssistantSessionUpdatedEvent a = new AssistantSessionUpdatedEvent("workflows");
        AssistantSessionUpdatedEvent b = new AssistantSessionUpdatedEvent("workflows");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentPageValue() {
        AssistantSessionUpdatedEvent a = new AssistantSessionUpdatedEvent("workflows");
        AssistantSessionUpdatedEvent b = new AssistantSessionUpdatedEvent("settings");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothPagesNull() {
        AssistantSessionUpdatedEvent a = new AssistantSessionUpdatedEvent(null);
        AssistantSessionUpdatedEvent b = new AssistantSessionUpdatedEvent(null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_onePageIsNull() {
        AssistantSessionUpdatedEvent a = new AssistantSessionUpdatedEvent("dashboard");
        AssistantSessionUpdatedEvent b = new AssistantSessionUpdatedEvent(null);

        assertThat(a).isNotEqualTo(b);
        assertThat(b).isNotEqualTo(a);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("dashboard");

        assertThat(event).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("dashboard");

        assertThat(event).isNotEqualTo("dashboard");
    }

    @Test
    void should_beEqualToItself() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("dashboard");

        assertThat(event).isEqualTo(event);
    }

    // --- toString ---

    @Test
    void should_containPageValue_when_toStringCalled() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("workflows");

        assertThat(event.toString()).contains("workflows");
    }

    @Test
    void should_containClassName_when_toStringCalled() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent("dashboard");

        assertThat(event.toString()).contains("AssistantSessionUpdatedEvent");
    }

    @Test
    void should_containNull_when_toStringCalledWithNullPage() {
        AssistantSessionUpdatedEvent event = new AssistantSessionUpdatedEvent(null);

        assertThat(event.toString()).contains("null");
    }
}
