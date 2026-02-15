package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockDefinitionTest {

    @Test
    void shouldStopOnFailure_defaultsToTrue() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("command", "test")))
                .build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_stopValue_returnsTrue() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("on_failure", "stop")))
                .build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_continueValue_returnsFalse() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(new HashMap<>(Map.of("on_failure", "continue")))
                .build();

        assertThat(block.shouldStopOnFailure()).isFalse();
    }

    @Test
    void builder_defaults_initializeCollections() {
        BlockDefinition block = BlockDefinition.builder()
                .type("test")
                .build();

        assertThat(block.getConfig()).isNotNull().isEmpty();
        assertThat(block.getRequiredCapabilities()).isNotNull().isEmpty();
    }
}
