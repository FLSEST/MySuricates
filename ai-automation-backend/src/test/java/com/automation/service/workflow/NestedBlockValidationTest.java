package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.entity.StepResult;
import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.automation.executor.ExecutionContext;
import com.automation.executor.ExecutionMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Both definition validations walk the same tree through {@code NestedBlocks}, so both must reach
 * the same containers. One fixture, one offending block per container, both checks (d15fb3a3).
 */
class NestedBlockValidationTest {

    private final BlockTypeValidator typeValidator = new BlockTypeValidator(new BlockTypeRegistry(List.of(
            executorFor("shell"), executorFor("simple"), executorFor("switch"), executorFor("foreach"))));

    /**
     * A switch whose case / default / plain-blocks container holds one nested block that is broken
     * in both ways at once: an unknown type and an unknown {@code execution_mode}.
     */
    private static BlockDefinition fixtureWithOffenderIn(String container) {
        Map<String, Object> offender = Map.of("type", "frobnicate", "name", "offender",
                "config", Map.of("execution_mode", "shel"));
        Map<String, Object> harmless = Map.of("type", "shell", "name", "fine",
                "config", Map.of("command", "ls"));

        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ mode }}");
        config.put("cases", List.of(Map.of("value", "a", "blocks",
                List.of("cases".equals(container) ? offender : harmless))));
        config.put("default", List.of("default".equals(container) ? offender : harmless));
        config.put("blocks", List.of("blocks".equals(container) ? offender : harmless));
        return BlockDefinition.builder().type("switch").name("router").config(config).build();
    }

    @ParameterizedTest
    @ValueSource(strings = {"cases", "default", "blocks"})
    void bothValidationsFindTheNestedBlock(String container) {
        List<BlockDefinition> blocks = List.of(fixtureWithOffenderIn(container));

        assertThatThrownBy(() -> typeValidator.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offender")
                .hasMessageContaining("frobnicate");
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("offender")
                .hasMessageContaining("shel");
    }

    @ParameterizedTest
    @ValueSource(strings = {"cases", "default", "blocks"})
    void bothValidationsReachTheSecondLevelOfTheSameContainer(String container) {
        Map<String, Object> deep = Map.of("type", "frobnicate", "name", "deep-offender",
                "config", Map.of("execution_mode", "bash"));
        Map<String, Object> innerConfig = new HashMap<>();
        if ("cases".equals(container)) {
            innerConfig.put("cases", List.of(Map.of("value", "x", "blocks", List.of(deep))));
        } else {
            innerConfig.put(container, List.of(deep));
        }
        Map<String, Object> wrapper = Map.of("type", "switch", "name", "inner", "config", innerConfig);
        Map<String, Object> config = new HashMap<>(Map.of("blocks", List.of(wrapper)));
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("foreach").name("loop").config(config).build());

        assertThatThrownBy(() -> typeValidator.rejectUnknown(blocks))
                .hasMessageContaining("deep-offender");
        assertThatThrownBy(() -> ExecutionMode.rejectUnknown(blocks))
                .hasMessageContaining("deep-offender");
    }

    @Test
    void aCleanTreePassesBothValidations() {
        Map<String, Object> config = new HashMap<>();
        config.put("expression", "{{ mode }}");
        config.put("cases", List.of(Map.of("value", "a", "blocks", List.of(
                Map.of("type", "shell", "name", "fine", "config", Map.of("execution_mode", "shell"))))));
        config.put("default", List.of(Map.of("type", "simple", "name", "fallback")));
        config.put("blocks", List.of(Map.of("type", "shell", "config", Map.of("execution_mode", "ai"))));
        List<BlockDefinition> blocks = List.of(
                BlockDefinition.builder().type("switch").name("router").config(config).build());

        assertThatCode(() -> typeValidator.rejectUnknown(blocks)).doesNotThrowAnyException();
        assertThatCode(() -> ExecutionMode.rejectUnknown(blocks)).doesNotThrowAnyException();
    }

    private static BlockExecutor executorFor(String type) {
        return new BlockExecutor() {
            @Override
            public StepResult execute(BlockDefinition block, ExecutionContext context) {
                return null;
            }

            @Override
            public Set<String> supportedTypes() {
                return Set.of(type);
            }
        };
    }
}
