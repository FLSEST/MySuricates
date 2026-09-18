package com.automation.service.workflow;

import com.automation.entity.BlockDefinition;
import com.automation.executor.BlockExecutor;
import com.automation.executor.BlockTypeRegistry;
import com.automation.executor.ExecutionContext;
import com.automation.entity.StepResult;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockTypeValidatorTest {

    private final BlockTypeValidator validator = new BlockTypeValidator(new BlockTypeRegistry(List.of(
            executorFor("shell"), executorFor("simple"), executorFor("switch"), executorFor("foreach"))));

    @Test
    void knownTypesPass() {
        assertThatCode(() -> validator.rejectUnknown(List.of(
                block("shell", "build"), block("simple", "review"))))
                .doesNotThrowAnyException();
    }

    @Test
    void nullAndEmptyBlockListsPass() {
        assertThatCode(() -> validator.rejectUnknown(null)).doesNotThrowAnyException();
        assertThatCode(() -> validator.rejectUnknown(List.of())).doesNotThrowAnyException();
    }

    @Test
    void unknownTopLevelTypeIsRejected_withTypeAndAllowedList() {
        List<BlockDefinition> blocks = List.of(block("frobnicate", "weird"));

        assertThatThrownBy(() -> validator.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate")
                .hasMessageContaining("weird")
                .hasMessageContaining("foreach, shell, simple, switch");
    }

    @Test
    void unknownTypeInSwitchCaseIsRejected() {
        BlockDefinition switchBlock = block("switch", "route");
        switchBlock.getConfig().put("cases", List.of(Map.of(
                "value", "a",
                "blocks", List.of(Map.of("type", "document", "name", "nested-doc")))));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(switchBlock)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("document")
                .hasMessageContaining("nested-doc");
    }

    @Test
    void unknownTypeInSwitchDefaultIsRejected() {
        BlockDefinition switchBlock = block("switch", "route");
        switchBlock.getConfig().put("default", List.of(Map.of("type", "todo-create")));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(switchBlock)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("todo-create");
    }

    @Test
    void unknownTypeInNestedBlocksListIsRejected() {
        BlockDefinition foreach = block("foreach", "loop");
        foreach.getConfig().put("blocks", List.of(Map.of("type", "frobnicate")));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(foreach)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    void recursesIntoSwitchInsideSwitch() {
        Map<String, Object> innerSwitch = Map.of(
                "type", "switch",
                "config", Map.of("cases", List.of(Map.of(
                        "value", "x", "blocks", List.of(Map.of("type", "frobnicate"))))));
        BlockDefinition outer = block("switch", "outer");
        outer.getConfig().put("cases", List.of(Map.of("value", "a", "blocks", List.of(innerSwitch))));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(outer)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frobnicate");
    }

    @Test
    void nestedKnownTypesPass() {
        BlockDefinition switchBlock = block("switch", "route");
        switchBlock.getConfig().put("cases", List.of(Map.of(
                "value", "a", "blocks", List.of(Map.of("type", "shell", "config", Map.of("command", "ls"))))));
        switchBlock.getConfig().put("default", List.of(Map.of("type", "simple")));

        assertThatCode(() -> validator.rejectUnknown(List.of(switchBlock))).doesNotThrowAnyException();
    }

    /** A blank or missing type behaves as before (the executor lookup reports it) — no new rejection. */
    @Test
    void blankTypeIsNotRejectedHere() {
        assertThatCode(() -> validator.rejectUnknown(List.of(block(null, "no-type"), block("  ", "blank"))))
                .doesNotThrowAnyException();
    }

    @Test
    void nonListConfigValuesAreIgnored() {
        BlockDefinition simple = block("simple", "plain");
        simple.getConfig().put("default", "some-string-default");
        simple.getConfig().put("cases", "not-a-list");
        simple.getConfig().put("blocks", 42);

        assertThatCode(() -> validator.rejectUnknown(List.of(simple))).doesNotThrowAnyException();
    }

    @Test
    void nullEntriesAreTolerated() {
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.add(null);
        blocks.add(block("shell", "ok"));

        assertThatCode(() -> validator.rejectUnknown(blocks)).doesNotThrowAnyException();
    }

    private static BlockDefinition block(String type, String name) {
        return BlockDefinition.builder().type(type).name(name).config(new HashMap<>()).build();
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
