package com.automation.util;

import com.automation.entity.BlockDefinition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BlockDefinitionListConverterTest {

    private BlockDefinitionListConverter converter;

    @BeforeEach
    void setUp() {
        converter = new BlockDefinitionListConverter();
    }

    // --- convertToDatabaseColumn ---

    @Test
    void should_returnNull_when_attributeIsNull() {
        String result = converter.convertToDatabaseColumn(null);

        assertThat(result).isNull();
    }

    @Test
    void should_returnNull_when_attributeIsEmptyList() {
        String result = converter.convertToDatabaseColumn(new ArrayList<>());

        assertThat(result).isNull();
    }

    @Test
    void should_serializeSingleBlock_when_attributeHasOneBlock() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "echo hello"))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(block));

        assertThat(result)
                .contains("\"type\":\"shell\"")
                .contains("\"command\"")
                .contains("\"echo hello\"");
    }

    @Test
    void should_serializeMultipleBlocks_when_attributeHasMultipleBlocks() {
        BlockDefinition shellBlock = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "ls -la"))
                .build();
        BlockDefinition simpleBlock = BlockDefinition.builder()
                .type("simple")
                .config(Map.of("prompt", "Analyze this"))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(shellBlock, simpleBlock));

        assertThat(result)
                .startsWith("[")
                .endsWith("]")
                .contains("\"shell\"")
                .contains("\"simple\"")
                .contains("\"ls -la\"")
                .contains("\"Analyze this\"");
    }

    @Test
    void should_serializeNestedConfig_when_blockHasNestedFields() {
        BlockDefinition block = BlockDefinition.builder()
                .type("agentic")
                .config(Map.of(
                        "goal", "Fix the bug",
                        "max_iterations", 10,
                        "require_approval", true
                ))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(block));

        assertThat(result)
                .contains("\"agentic\"")
                .contains("\"Fix the bug\"")
                .contains("10")
                .contains("true");
    }

    @Test
    void should_serializeSkillIds_when_blockHasSkillIds() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .skillIds(List.of("builtin-code-review", "builtin-test-generator"))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(block));

        assertThat(result)
                .contains("\"builtin-code-review\"")
                .contains("\"builtin-test-generator\"");
    }

    @Test
    void should_serializeRequiredCapabilities_when_blockHasCapabilities() {
        BlockDefinition block = BlockDefinition.builder()
                .type("skill-capability")
                .requiredCapabilities(List.of("lint", "ocr"))
                .build();

        String result = converter.convertToDatabaseColumn(List.of(block));

        assertThat(result)
                .contains("\"lint\"")
                .contains("\"ocr\"");
    }

    // --- convertToEntityAttribute ---

    @Test
    void should_returnEmptyList_when_dbDataIsNull() {
        List<BlockDefinition> result = converter.convertToEntityAttribute(null);

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsEmpty() {
        List<BlockDefinition> result = converter.convertToEntityAttribute("");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_returnEmptyList_when_dbDataIsBlank() {
        List<BlockDefinition> result = converter.convertToEntityAttribute("   ");

        assertThat(result).isNotNull().isEmpty();
    }

    @Test
    void should_deserializeSingleBlock_when_dbDataHasOneBlock() {
        String json = "[{\"type\":\"shell\",\"config\":{\"command\":\"echo test\"},\"requiredCapabilities\":[],\"skillIds\":[]}]";

        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getType()).isEqualTo("shell");
        assertThat(result.get(0).getConfig()).containsEntry("command", "echo test");
    }

    @Test
    void should_deserializeMultipleBlocks_when_dbDataHasMultipleBlocks() {
        String json = "[{\"type\":\"shell\",\"config\":{},\"requiredCapabilities\":[],\"skillIds\":[]},{\"type\":\"simple\",\"config\":{\"prompt\":\"hello\"},\"requiredCapabilities\":[],\"skillIds\":[]}]";

        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getType()).isEqualTo("shell");
        assertThat(result.get(1).getType()).isEqualTo("simple");
        assertThat(result.get(1).getConfig()).containsEntry("prompt", "hello");
    }

    @Test
    void should_deserializeBlockWithSkillIds_when_dbDataContainsSkillIds() {
        String json = "[{\"type\":\"simple\",\"config\":{},\"requiredCapabilities\":[],\"skillIds\":[\"builtin-lint\",\"builtin-test\"]}]";

        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getSkillIds()).containsExactly("builtin-lint", "builtin-test");
    }

    @Test
    void should_deserializeBlockWithCapabilities_when_dbDataContainsCapabilities() {
        String json = "[{\"type\":\"skill-capability\",\"config\":{},\"requiredCapabilities\":[\"lint\",\"ocr\"],\"skillIds\":[]}]";

        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getRequiredCapabilities()).containsExactly("lint", "ocr");
    }

    @Test
    void should_throwException_when_dbDataIsMalformedJson() {
        String malformed = "[broken json!!!";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(malformed))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to deserialize JSON to blocks");
    }

    @Test
    void should_throwException_when_dbDataIsJsonObjectInsteadOfArray() {
        String jsonObject = "{\"type\":\"shell\"}";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(jsonObject))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_throwException_when_dbDataIsPlainString() {
        String plainString = "\"just a string\"";

        assertThatThrownBy(() -> converter.convertToEntityAttribute(plainString))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- Roundtrip ---

    @Test
    void should_roundtrip_when_singleBlock() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "echo roundtrip"))
                .build();
        List<BlockDefinition> original = List.of(block);

        String json = converter.convertToDatabaseColumn(original);
        List<BlockDefinition> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getType()).isEqualTo("shell");
        assertThat(restored.get(0).getConfig()).containsEntry("command", "echo roundtrip");
    }

    @Test
    void should_roundtrip_when_multipleBlocksWithAllFields() {
        BlockDefinition shellBlock = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "npm test", "timeout_seconds", 120))
                .requiredCapabilities(List.of())
                .skillIds(List.of())
                .build();
        BlockDefinition agenticBlock = BlockDefinition.builder()
                .type("agentic")
                .config(Map.of("goal", "Fix bug", "max_iterations", 5))
                .requiredCapabilities(List.of("code-review"))
                .skillIds(List.of("builtin-code-review"))
                .build();
        List<BlockDefinition> original = new ArrayList<>();
        original.add(shellBlock);
        original.add(agenticBlock);

        String json = converter.convertToDatabaseColumn(original);
        List<BlockDefinition> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).getType()).isEqualTo("shell");
        assertThat(restored.get(0).getConfig()).containsEntry("command", "npm test");
        assertThat(restored.get(0).getConfig()).containsEntry("timeout_seconds", 120);
        assertThat(restored.get(1).getType()).isEqualTo("agentic");
        assertThat(restored.get(1).getConfig()).containsEntry("goal", "Fix bug");
        assertThat(restored.get(1).getRequiredCapabilities()).containsExactly("code-review");
        assertThat(restored.get(1).getSkillIds()).containsExactly("builtin-code-review");
    }

    @Test
    void should_roundtripNull_when_nullInput() {
        String json = converter.convertToDatabaseColumn(null);
        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    @Test
    void should_roundtripEmptyList_when_emptyListInput() {
        String json = converter.convertToDatabaseColumn(new ArrayList<>());
        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        assertThat(json).isNull();
        assertThat(result).isEmpty();
    }

    // --- Edge cases ---

    @Test
    void should_returnMutableList_when_deserializing() {
        String json = "[{\"type\":\"shell\",\"config\":{},\"requiredCapabilities\":[],\"skillIds\":[]}]";

        List<BlockDefinition> result = converter.convertToEntityAttribute(json);

        // Verify the list is mutable (important for JPA entity usage)
        result.add(BlockDefinition.builder().type("simple").build());
        assertThat(result).hasSize(2);
    }

    @Test
    void should_returnMutableEmptyList_when_dbDataIsNull() {
        List<BlockDefinition> result = converter.convertToEntityAttribute(null);

        // Verify the empty list is mutable
        result.add(BlockDefinition.builder().type("shell").build());
        assertThat(result).hasSize(1);
    }

    @Test
    void should_handleBlockWithEmptyConfig_when_configIsEmpty() {
        BlockDefinition block = BlockDefinition.builder()
                .type("simple")
                .config(Map.of())
                .build();

        String json = converter.convertToDatabaseColumn(List.of(block));
        List<BlockDefinition> restored = converter.convertToEntityAttribute(json);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).getType()).isEqualTo("simple");
        assertThat(restored.get(0).getConfig()).isEmpty();
    }

    // --- d3aa0b28: Felder neben config gehen nicht mehr still verloren ---

    @Test
    void unknownBlockFields_surviveTheRoundTrip_andWellFormedBlocksStayUnchanged() {
        String json = "[{\"type\":\"shell\",\"name\":\"check\",\"config\":{\"command\":\"ls\"},"
                + "\"on_failure\":\"continue\"}]";

        List<BlockDefinition> blocks = converter.convertToEntityAttribute(json);

        assertThat(blocks.get(0).unknownFields()).containsExactly(Map.entry("on_failure", "continue"));
        assertThat(blocks.get(0).shouldStopOnFailure()).as("an der falschen Stelle wirkt es nicht").isTrue();
        assertThat(converter.convertToDatabaseColumn(blocks)).contains("\"on_failure\":\"continue\"");

        BlockDefinition clean = BlockDefinition.builder().type("shell").config(Map.of("command", "ls")).build();
        assertThat(converter.convertToDatabaseColumn(List.of(clean)))
                .doesNotContain("unknownFields").doesNotContain("on_failure");
    }

    @Test
    void rowWrittenByANewerVersion_withFieldsThisVersionDoesNotKnow_isReadAndKept() {
        String newer = "[{\"type\":\"simple\",\"config\":{\"command\":\"x\"},\"futureField\":{\"a\":1},"
                + "\"skillIds\":[\"s\"],\"anotherOne\":true}]";

        List<BlockDefinition> blocks = converter.convertToEntityAttribute(newer);

        assertThat(blocks).hasSize(1);
        assertThat(blocks.get(0).getSkillIds()).containsExactly("s");
        assertThat(blocks.get(0).unknownFields()).containsOnlyKeys("anotherOne", "futureField");
        assertThat(converter.convertToEntityAttribute(converter.convertToDatabaseColumn(blocks)).get(0).unknownFields())
                .isEqualTo(blocks.get(0).unknownFields());
    }
}
