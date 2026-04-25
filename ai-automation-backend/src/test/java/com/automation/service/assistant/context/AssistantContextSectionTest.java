package com.automation.service.assistant.context;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class AssistantContextSectionTest {

    @Test
    void append_shouldReceiveStringBuilderAndCompactFlag_whenCalled() {
        AtomicReference<StringBuilder> capturedBuilder = new AtomicReference<>();
        AtomicBoolean capturedCompact = new AtomicBoolean();

        AssistantContextSection section = (sb, compact) -> {
            capturedBuilder.set(sb);
            capturedCompact.set(compact);
            sb.append("section-output");
        };

        StringBuilder builder = new StringBuilder("prefix:");
        section.append(builder, true);

        assertThat(capturedBuilder.get()).isSameAs(builder);
        assertThat(capturedCompact.get()).isTrue();
        assertThat(builder.toString()).isEqualTo("prefix:section-output");
    }

    @Test
    void append_shouldSupportNonCompactMode_whenCompactFalse() {
        AtomicBoolean capturedCompact = new AtomicBoolean(true);

        AssistantContextSection section = (sb, compact) -> {
            capturedCompact.set(compact);
            sb.append(compact ? "[compact]" : "[full]");
        };

        StringBuilder builder = new StringBuilder();
        section.append(builder, false);

        assertThat(capturedCompact.get()).isFalse();
        assertThat(builder.toString()).isEqualTo("[full]");
    }

    @Test
    void append_shouldAllowMultipleSectionsToWriteSequentially() {
        AssistantContextSection first = (sb, compact) -> sb.append("A");
        AssistantContextSection second = (sb, compact) -> sb.append("B");
        AssistantContextSection third = (sb, compact) -> sb.append("C");

        StringBuilder builder = new StringBuilder();
        first.append(builder, false);
        second.append(builder, false);
        third.append(builder, false);

        assertThat(builder.toString()).isEqualTo("ABC");
    }

    @Test
    void append_shouldAllowEmptyImplementation_whenSectionContributesNothing() {
        AssistantContextSection emptySection = (sb, compact) -> {
            // intentionally writes nothing
        };

        StringBuilder builder = new StringBuilder("unchanged");
        emptySection.append(builder, true);

        assertThat(builder.toString()).isEqualTo("unchanged");
    }

    @Test
    void append_isFunctionalInterface_canBeUsedAsLambda() {
        AssistantContextSection lambda = (sb, compact) -> sb.append("lambda-works");

        StringBuilder builder = new StringBuilder();
        lambda.append(builder, true);

        assertThat(lambda).isNotNull();
        assertThat(builder.toString()).isEqualTo("lambda-works");
    }
}
