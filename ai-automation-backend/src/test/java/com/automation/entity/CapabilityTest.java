package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CapabilityTest {

    // --- Builder defaults ---

    @Test
    void should_defaultInputSchemaToEmptyMap_when_usingBuilder() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getInputSchema()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultOutputSchemaToEmptyMap_when_usingBuilder() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getOutputSchema()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultAsyncToFalse_when_usingBuilder() {
        Capability capability = Capability.builder().build();

        assertThat(capability.isAsync()).isFalse();
    }

    @Test
    void should_haveNullId_when_builtWithDefaults() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getId()).isNull();
    }

    @Test
    void should_haveNullSkill_when_builtWithDefaults() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getSkill()).isNull();
    }

    @Test
    void should_haveNullName_when_builtWithDefaults() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getName()).isNull();
    }

    @Test
    void should_haveNullDescription_when_builtWithDefaults() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getDescription()).isNull();
    }

    @Test
    void should_haveNullHandlerClass_when_builtWithDefaults() {
        Capability capability = Capability.builder().build();

        assertThat(capability.getHandlerClass()).isNull();
    }

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultInputSchemaToEmptyMap_when_usingNoArgsConstructor() {
        Capability capability = new Capability();

        assertThat(capability.getInputSchema()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultOutputSchemaToEmptyMap_when_usingNoArgsConstructor() {
        Capability capability = new Capability();

        assertThat(capability.getOutputSchema()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultAsyncToFalse_when_usingNoArgsConstructor() {
        Capability capability = new Capability();

        assertThat(capability.isAsync()).isFalse();
    }

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        Capability capability = new Capability();

        assertThat(capability.getId()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        Capability capability = new Capability();

        capability.setId("cap-123");

        assertThat(capability.getId()).isEqualTo("cap-123");
    }

    @Test
    void should_storeName_when_setViaSetter() {
        Capability capability = new Capability();

        capability.setName("my-capability");

        assertThat(capability.getName()).isEqualTo("my-capability");
    }

    @Test
    void should_storeDescription_when_setViaSetter() {
        Capability capability = new Capability();

        capability.setDescription("Does something useful");

        assertThat(capability.getDescription()).isEqualTo("Does something useful");
    }

    @Test
    void should_storeHandlerClass_when_setViaSetter() {
        Capability capability = new Capability();

        capability.setHandlerClass("com.example.MyHandler");

        assertThat(capability.getHandlerClass()).isEqualTo("com.example.MyHandler");
    }

    @Test
    void should_storeSkill_when_setViaSetter() {
        Capability capability = new Capability();
        Skill skill = new Skill();
        skill.setId("skill-001");

        capability.setSkill(skill);

        assertThat(capability.getSkill()).isSameAs(skill);
        assertThat(capability.getSkill().getId()).isEqualTo("skill-001");
    }

    @Test
    void should_storeInputSchema_when_setViaSetter() {
        Capability capability = new Capability();
        Map<String, Object> schema = new HashMap<>(Map.of("type", "string"));

        capability.setInputSchema(schema);

        assertThat(capability.getInputSchema()).containsEntry("type", "string");
    }

    @Test
    void should_storeOutputSchema_when_setViaSetter() {
        Capability capability = new Capability();
        Map<String, Object> schema = new HashMap<>(Map.of("type", "object"));

        capability.setOutputSchema(schema);

        assertThat(capability.getOutputSchema()).containsEntry("type", "object");
    }

    @Test
    void should_storeAsync_when_setViaSetter() {
        Capability capability = new Capability();

        capability.setAsync(true);

        assertThat(capability.isAsync()).isTrue();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Skill skill = new Skill();
        skill.setId("skill-abc");
        Map<String, Object> inputSchema = new HashMap<>(Map.of("param", "value"));
        Map<String, Object> outputSchema = new HashMap<>(Map.of("result", "string"));

        Capability capability = new Capability(
                "cap-id", skill, "search", "Searches data",
                "com.example.SearchHandler", inputSchema, outputSchema, true, false
        );

        assertThat(capability.getId()).isEqualTo("cap-id");
        assertThat(capability.getSkill()).isSameAs(skill);
        assertThat(capability.getName()).isEqualTo("search");
        assertThat(capability.getDescription()).isEqualTo("Searches data");
        assertThat(capability.getHandlerClass()).isEqualTo("com.example.SearchHandler");
        assertThat(capability.getInputSchema()).containsEntry("param", "value");
        assertThat(capability.getOutputSchema()).containsEntry("result", "string");
        assertThat(capability.isAsync()).isTrue();
    }

    // --- Builder with explicit values ---

    @Test
    void should_useExplicitValues_when_builderOverridesDefaults() {
        Skill skill = new Skill();
        skill.setId("skill-xyz");
        Map<String, Object> inputSchema = new HashMap<>(Map.of("key", "val"));

        Capability capability = Capability.builder()
                .id("cap-explicit")
                .skill(skill)
                .name("transform")
                .description("Transforms data")
                .handlerClass("com.example.TransformHandler")
                .inputSchema(inputSchema)
                .async(true)
                .build();

        assertThat(capability.getId()).isEqualTo("cap-explicit");
        assertThat(capability.getSkill()).isSameAs(skill);
        assertThat(capability.getName()).isEqualTo("transform");
        assertThat(capability.getDescription()).isEqualTo("Transforms data");
        assertThat(capability.getHandlerClass()).isEqualTo("com.example.TransformHandler");
        assertThat(capability.getInputSchema()).containsEntry("key", "val");
        assertThat(capability.getOutputSchema()).isNotNull().isEmpty();
        assertThat(capability.isAsync()).isTrue();
    }

    // --- Schema map mutability ---

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultInputSchema() {
        Capability capability = Capability.builder().build();

        capability.getInputSchema().put("field", "text");

        assertThat(capability.getInputSchema()).hasSize(1).containsEntry("field", "text");
    }

    @Test
    void should_allowAddingEntries_when_usingBuilderDefaultOutputSchema() {
        Capability capability = Capability.builder().build();

        capability.getOutputSchema().put("result", 42);

        assertThat(capability.getOutputSchema()).hasSize(1).containsEntry("result", 42);
    }
}
