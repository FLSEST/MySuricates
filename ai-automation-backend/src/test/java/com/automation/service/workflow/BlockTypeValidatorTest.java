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

    /**
     * A block without a type is as unexecutable as one with an invented type — and worse in a
     * switch case, where it used to fall through into the AI branch (TODO 89dd9e34).
     */
    @Test
    void missingTypeIsRejected() {
        List<BlockDefinition> blocks = List.of(block(null, "no-type"));

        assertThatThrownBy(() -> validator.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no-type")
                .hasMessageContaining("foreach, shell, simple, switch");
    }

    @Test
    void blankTypeIsRejected() {
        List<BlockDefinition> blocks = List.of(block("  ", "blank"));

        assertThatThrownBy(() -> validator.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank");
    }

    @Test
    void unnamedBlockWithoutTypeIsRejectedToo() {
        List<BlockDefinition> blocks = List.of(block(null, null));

        assertThatThrownBy(() -> validator.rejectUnknown(blocks))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingTypeInANestedBlockIsRejected() {
        BlockDefinition switchBlock = block("switch", "route");
        switchBlock.getConfig().put("cases", List.of(Map.of(
                "value", "a",
                "blocks", List.of(Map.of("name", "typeless", "config", Map.of("command", "do it"))))));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(switchBlock)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("typeless");
    }

    @Test
    void blankTypeInASwitchDefaultIsRejected() {
        BlockDefinition switchBlock = block("switch", "route");
        switchBlock.getConfig().put("default", List.of(Map.of("type", "", "name", "empty-type")));

        assertThatThrownBy(() -> validator.rejectUnknown(List.of(switchBlock)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty-type");
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
