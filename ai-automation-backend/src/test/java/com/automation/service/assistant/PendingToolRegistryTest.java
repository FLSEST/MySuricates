package com.automation.service.assistant;

import com.automation.dto.PendingTool;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    void register_withLabel_carriesSubjectAndParams() {
        PendingToolRegistry registry = new PendingToolRegistry();

        PendingTool tool = registry.register("start_workflow",
                new PendingToolLabel("Start workflow \"Daily\"", "Daily", java.util.Map.of("ref", "id")), "{}");

        assertThat(tool.description()).isEqualTo("Start workflow \"Daily\"");
        assertThat(tool.subject()).isEqualTo("Daily");
        assertThat(tool.params()).containsEntry("ref", "id");
        assertThat(registry.peek(tool.id())).contains(tool);
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

    @Test
    void take_expiredTool_returnsEmptyAndRemoves() {
        PendingToolRegistry registry = new PendingToolRegistry();
        String id = UUID.randomUUID().toString();
        PendingTool expired = new PendingTool(id, "create_todo", "old",
                "{}", Instant.now().minusSeconds(60));
        injectRaw(registry, id, expired);

        // take() must filter the expired entry — the controller relies on this to return 410.
        assertThat(registry.take(id)).isEmpty();
        // Entry is also removed so cleanupExpired doesn't need to run for the controller path to work.
        assertThat(registry.size()).isZero();
    }

    @Test
    void peek_expiredTool_returnsEmpty() {
        PendingToolRegistry registry = new PendingToolRegistry();
        String id = UUID.randomUUID().toString();
        PendingTool expired = new PendingTool(id, "create_todo", "old",
                "{}", Instant.now().minusSeconds(60));
        injectRaw(registry, id, expired);

        assertThat(registry.peek(id)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private static void injectRaw(PendingToolRegistry registry, String id, PendingTool tool) {
        ConcurrentHashMap<String, PendingTool> pending =
                (ConcurrentHashMap<String, PendingTool>) ReflectionTestUtils.getField(registry, "pending");
        pending.put(id, tool);
    }
}
