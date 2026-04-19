package com.automation.service.assistant;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PendingToolRegistryTest {

    @Test
    void register_returnsPendingWithIdAndExpiry() {
        PendingToolRegistry registry = new PendingToolRegistry();

        PendingTool tool = registry.register("create_todo", "TODO anlegen", "{\"title\":\"x\"}");

        assertThat(tool.id()).isNotBlank();
        assertThat(tool.type()).isEqualTo("create_todo");
        assertThat(tool.description()).isEqualTo("TODO anlegen");
        assertThat(tool.payloadJson()).isEqualTo("{\"title\":\"x\"}");
        assertThat(tool.expiresAt()).isNotNull();
        assertThat(tool.isExpired()).isFalse();
        assertThat(registry.size()).isEqualTo(1);
    }

    @Test
    void take_returnsAndRemovesEntry() {
        PendingToolRegistry registry = new PendingToolRegistry();
        PendingTool tool = registry.register("create_todo", "x", "{}");

        assertThat(registry.take(tool.id())).hasValue(tool);
        // Second take: already consumed
        assertThat(registry.take(tool.id())).isEmpty();
        assertThat(registry.size()).isZero();
    }

    @Test
    void take_unknownId_returnsEmpty() {
        PendingToolRegistry registry = new PendingToolRegistry();
        assertThat(registry.take("does-not-exist")).isEmpty();
    }

    @Test
    void peek_doesNotConsume() {
        PendingToolRegistry registry = new PendingToolRegistry();
        PendingTool tool = registry.register("start_workflow", "start X", "{}");

        assertThat(registry.peek(tool.id())).hasValue(tool);
        assertThat(registry.size()).isEqualTo(1);
        assertThat(registry.take(tool.id())).hasValue(tool); // still takeable
    }
}
