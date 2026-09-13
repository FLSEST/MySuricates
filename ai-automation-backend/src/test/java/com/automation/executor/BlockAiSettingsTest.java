package com.automation.executor;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BlockAiSettingsTest {

    private static BlockDefinition block(Map<String, Object> config) {
        return BlockDefinition.builder().type("simple").config(new HashMap<>(config)).build();
    }

    @Test
    void providerFor_blockWinsOverWorkflow() {
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", "PI")), "CODEX")).isEqualTo("PI");
    }

    @Test
    void providerFor_fallsBackToWorkflow_thenNull() {
        assertThat(BlockAiSettings.providerFor(block(Map.of()), "CODEX")).isEqualTo("CODEX");
        assertThat(BlockAiSettings.providerFor(block(Map.of()), null)).isNull();
        assertThat(BlockAiSettings.providerFor(null, "CODEX")).isEqualTo("CODEX");
    }

    @Test
    void providerFor_blankOrNonStringValues_areIgnored() {
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", "  ")), "CODEX")).isEqualTo("CODEX");
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", 42)), "CODEX")).isEqualTo("CODEX");
        assertThat(BlockAiSettings.providerFor(block(Map.of("provider", " pi ")), "CODEX")).isEqualTo("pi");
    }

    @Test
    void modelFor_blockWinsOverWorkflow() {
        assertThat(BlockAiSettings.modelFor(block(Map.of("model", "opus")), "CLAUDE", "sonnet")).isEqualTo("opus");
    }

    @Test
    void modelFor_inheritsWorkflowModel_whenBlockKeepsProvider() {
        assertThat(BlockAiSettings.modelFor(block(Map.of()), "CLAUDE", "sonnet")).isEqualTo("sonnet");
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "claude")), "CLAUDE", "sonnet")).isEqualTo("sonnet");
    }

    @Test
    void modelFor_dropsWorkflowModel_whenBlockSwitchesProvider() {
        // model ids are provider-specific: "sonnet" (Claude alias) is meaningless for pi
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "PI")), "CLAUDE", "sonnet")).isNull();
        assertThat(BlockAiSettings.modelFor(block(Map.of("provider", "PI")), null, "sonnet")).isNull();
    }

    @Test
    void modelFor_nothingSet_isNull() {
        assertThat(BlockAiSettings.modelFor(block(Map.of()), null, null)).isNull();
        assertThat(BlockAiSettings.modelFor(null, "CLAUDE", null)).isNull();
    }

    @Test
    void effortFor_blockWins_invalidIgnored() {
        assertThat(BlockAiSettings.effortFor(block(Map.of("effort", "XHIGH")), "low")).isEqualTo("xhigh");
        assertThat(BlockAiSettings.effortFor(block(Map.of("effort", "turbo")), "low")).isEqualTo("low");
        assertThat(BlockAiSettings.effortFor(block(Map.of()), null)).isNull();
    }
}
