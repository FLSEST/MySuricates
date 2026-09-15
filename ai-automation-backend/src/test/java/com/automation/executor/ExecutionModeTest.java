package com.automation.executor;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatNoException;

class ExecutionModeTest {

    private static Map<String, Object> mode(Object value) {
        Map<String, Object> config = new HashMap<>();
        config.put("execution_mode", value);
        return config;
    }

    @Test
    void aiIsTheDefaultAndClaudeStaysALegacyAlias() {
        assertThat(ExecutionMode.isShell(Map.of())).isFalse();
        assertThat(ExecutionMode.isShell(null)).isFalse();
        assertThat(ExecutionMode.isShell(mode(null))).isFalse();
        assertThat(ExecutionMode.isShell(mode("ai"))).isFalse();
        assertThat(ExecutionMode.isShell(mode("claude"))).isFalse();
        assertThat(ExecutionMode.isShell(mode("shell"))).isTrue();
    }

    @Test
    void unknownValuesNoLongerRunSilentlyAsAi() {
        assertThatThrownBy(() -> ExecutionMode.isShell(mode("shel")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown execution_mode 'shel'")
                .hasMessageContaining("\"ai\"").hasMessageContaining("\"shell\"");
        assertThatThrownBy(() -> ExecutionMode.isShell(mode("pi"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExecutionMode.isShell(mode(true))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectUnknownChecksTopLevelAndBlocksNestedInSwitchCases() {
        BlockDefinition ok = BlockDefinition.builder().type("foreach").config(mode("ai")).build();
        assertThatNoException().isThrownBy(() -> ExecutionMode.rejectUnknown(List.of(ok)));
        assertThatNoException().isThrownBy(() -> ExecutionMode.rejectUnknown(null));

        BlockDefinition typo = BlockDefinition.builder().type("retry").name("Tests").config(mode("SHELL")).build();
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(ok, typo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Block 'Tests': unknown execution_mode 'SHELL'");

        Map<String, Object> switchConfig = new HashMap<>(Map.of(
                "expression", "{{ mode }}",
                "cases", List.of(Map.of("value", "a", "blocks", List.of(
                        Map.of("type", "conditional", "config", Map.of("execution_mode", "shell"))))),
                "default", List.of(Map.of("type", "while", "name", "fallback",
                        "config", Map.of("execution_mode", "bash")))));
        BlockDefinition switchBlock = BlockDefinition.builder().type("switch").config(switchConfig).build();
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(List.of(switchBlock)))
                .hasMessageStartingWith("Block 'fallback': unknown execution_mode 'bash'");
    }
}
