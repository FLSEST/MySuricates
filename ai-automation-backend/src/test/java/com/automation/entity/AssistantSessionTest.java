package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantSessionTest {

    // --- Builder defaults ---

    @Test
    void should_defaultMessagesToEmptyJsonArray_when_usingBuilder() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getMessages()).isEqualTo("[]");
    }

    @Test
    void should_defaultMessageCountToZero_when_usingBuilder() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getMessageCount()).isZero();
    }

    @Test
    void should_haveNullTimestamps_when_builtWithDefaults() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getStartedAt()).isNull();
        assertThat(session.getEndedAt()).isNull();
    }

    @Test
    void should_haveNullId_when_builtWithDefaults() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getId()).isNull();
    }

    @Test
    void should_haveNullPage_when_builtWithDefaults() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getPage()).isNull();
    }

    @Test
    void should_haveNullEntityId_when_builtWithDefaults() {
        AssistantSession session = AssistantSession.builder().build();

        assertThat(session.getEntityId()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        AssistantSession session = new AssistantSession();

        session.setId("session-123");

        assertThat(session.getId()).isEqualTo("session-123");
    }

    @Test
    void should_storePage_when_setViaSetter() {
        AssistantSession session = new AssistantSession();

        session.setPage("workflows");

        assertThat(session.getPage()).isEqualTo("workflows");
    }

    @Test
    void should_storeEntityId_when_setViaSetter() {
        AssistantSession session = new AssistantSession();

        session.setEntityId("entity-456");

        assertThat(session.getEntityId()).isEqualTo("entity-456");
    }

    @Test
    void should_storeMessages_when_setViaSetter() {
        AssistantSession session = new AssistantSession();
        String messages = "[{\"role\":\"user\",\"content\":\"hello\"}]";

        session.setMessages(messages);

        assertThat(session.getMessages()).isEqualTo(messages);
    }

    @Test
    void should_storeMessageCount_when_setViaSetter() {
        AssistantSession session = new AssistantSession();

        session.setMessageCount(5);

        assertThat(session.getMessageCount()).isEqualTo(5);
    }

    @Test
    void should_storeStartedAt_when_setViaSetter() {
        AssistantSession session = new AssistantSession();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        session.setStartedAt(ts);

        assertThat(session.getStartedAt()).isEqualTo(ts);
    }

    @Test
    void should_storeEndedAt_when_setViaSetter() {
        AssistantSession session = new AssistantSession();
        Instant ts = Instant.parse("2024-06-15T11:00:00Z");

        session.setEndedAt(ts);

        assertThat(session.getEndedAt()).isEqualTo(ts);
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getId()).isNull();
    }

    @Test
    void should_defaultPageToNull_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getPage()).isNull();
    }

    @Test
    void should_defaultEntityIdToNull_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getEntityId()).isNull();
    }

    @Test
    void should_defaultMessageCountToZero_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getMessageCount()).isZero();
    }

    @Test
    void should_defaultStartedAtToNull_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getStartedAt()).isNull();
    }

    @Test
    void should_defaultEndedAtToNull_when_usingNoArgsConstructor() {
        AssistantSession session = new AssistantSession();

        assertThat(session.getEndedAt()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant started = Instant.parse("2024-06-15T10:00:00Z");
        Instant ended = Instant.parse("2024-06-15T10:30:00Z");
        String messages = "[{\"role\":\"user\",\"content\":\"test\"}]";

        AssistantSession session = new AssistantSession(
                "session-id", "workflows", "entity-789",
                messages, 3, started, ended
        );

        assertThat(session.getId()).isEqualTo("session-id");
        assertThat(session.getPage()).isEqualTo("workflows");
        assertThat(session.getEntityId()).isEqualTo("entity-789");
        assertThat(session.getMessages()).isEqualTo(messages);
        assertThat(session.getMessageCount()).isEqualTo(3);
        assertThat(session.getStartedAt()).isEqualTo(started);
        assertThat(session.getEndedAt()).isEqualTo(ended);
    }

    // --- Builder with explicit values ---

    @Test
    void should_useExplicitValues_when_builderOverridesDefaults() {
        Instant started = Instant.parse("2024-01-01T00:00:00Z");
        String customMessages = "[{\"role\":\"assistant\",\"content\":\"hi\"}]";

        AssistantSession session = AssistantSession.builder()
                .id("custom-id")
                .page("todos")
                .entityId("todo-123")
                .messages(customMessages)
                .messageCount(10)
                .startedAt(started)
                .build();

        assertThat(session.getId()).isEqualTo("custom-id");
        assertThat(session.getPage()).isEqualTo("todos");
        assertThat(session.getEntityId()).isEqualTo("todo-123");
        assertThat(session.getMessages()).isEqualTo(customMessages);
        assertThat(session.getMessageCount()).isEqualTo(10);
        assertThat(session.getStartedAt()).isEqualTo(started);
        assertThat(session.getEndedAt()).isNull();
    }

    // --- prePersist callback ---

    @Test
    void should_setStartedAtToNow_when_prePersistCalledWithNullStartedAt() {
        AssistantSession session = new AssistantSession();
        assertThat(session.getStartedAt()).isNull();

        Instant before = Instant.now();
        session.prePersist();
        Instant after = Instant.now();

        assertThat(session.getStartedAt()).isNotNull();
        assertThat(session.getStartedAt()).isBetween(before, after);
    }

    @Test
    void should_notOverrideStartedAt_when_prePersistCalledWithExistingValue() {
        Instant existingTimestamp = Instant.parse("2024-01-01T00:00:00Z");
        AssistantSession session = new AssistantSession();
        session.setStartedAt(existingTimestamp);

        session.prePersist();

        assertThat(session.getStartedAt()).isEqualTo(existingTimestamp);
    }
}
