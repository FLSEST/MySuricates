package com.automation.entity;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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

    @ParameterizedTest
    @ValueSource(strings = {"Continue", "CONTINUE", " continue ", "continue\n"})
    void shouldStopOnFailure_continueInAnyCaseOrPadded_returnsFalse(String value) {
        assertThat(blockWith(value).shouldStopOnFailure()).isFalse();
    }

    /** Fail-closed (2b361abe): anything that is not clearly "continue" stops, never continues silently. */
    @ParameterizedTest
    @ValueSource(strings = {"fail", "Stop", "STOP", "abort", "contiue", "", "   ", "continue please"})
    void shouldStopOnFailure_unknownValue_stopsLikeStop(String value) {
        assertThat(blockWith(value).shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_nonStringValue_stops() {
        assertThat(blockWith(Boolean.FALSE).shouldStopOnFailure()).isTrue();
        assertThat(blockWith(0).shouldStopOnFailure()).isTrue();
        assertThat(blockWith(Map.of("mode", "continue")).shouldStopOnFailure()).isTrue();
    }

    @Test
    void shouldStopOnFailure_nullConfig_stops() {
        BlockDefinition block = BlockDefinition.builder().type("simple").config(null).build();

        assertThat(block.shouldStopOnFailure()).isTrue();
    }

    private static BlockDefinition blockWith(Object onFailure) {
        Map<String, Object> config = new HashMap<>();
        config.put("on_failure", onFailure);
        return BlockDefinition.builder().type("simple").config(config).build();
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
