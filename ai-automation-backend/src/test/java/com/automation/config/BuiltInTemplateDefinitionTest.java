package com.automation.config;

import com.automation.entity.BlockDefinition;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInTemplateDefinitionTest {

    @Test
    void should_haveJsonIgnorePropertiesAnnotation_when_classIsInspected() {
        assertThat(BuiltInTemplateDefinition.class.isAnnotationPresent(JsonIgnoreProperties.class))
                .isTrue();
        assertThat(BuiltInTemplateDefinition.class.getAnnotation(JsonIgnoreProperties.class).ignoreUnknown())
                .isTrue();
    }

    @Test
    void should_storeAllFields_when_constructedWithValues() {
        BlockDefinition block = BlockDefinition.builder()
                .type("shell")
                .config(Map.of("command", "echo hello"))
                .requiredCapabilities(List.of("filesystem"))
                .skillIds(List.of("skill-1"))
                .build();

        BuiltInTemplateDefinition definition = new BuiltInTemplateDefinition(
                "template-1",
                "🧪",
                Map.of("de", "Vorlage", "en", "Template"),
                Map.of("de", "Beschreibung", "en", "Description"),
                "testing",
                7,
                "1.2.3",
                "claude-sonnet-4-6",
                "Keep it small.",
                List.of(block)
        );

        assertThat(definition.id()).isEqualTo("template-1");
        assertThat(definition.icon()).isEqualTo("🧪");
        assertThat(definition.name()).containsEntry("de", "Vorlage").containsEntry("en", "Template");
        assertThat(definition.description()).containsEntry("de", "Beschreibung").containsEntry("en", "Description");
        assertThat(definition.category()).isEqualTo("testing");
        assertThat(definition.sortOrder()).isEqualTo(7);
        assertThat(definition.version()).isEqualTo("1.2.3");
        assertThat(definition.model()).isEqualTo("claude-sonnet-4-6");
        assertThat(definition.instructions()).isEqualTo("Keep it small.");
        assertThat(definition.blocks()).singleElement().satisfies(savedBlock -> {
            assertThat(savedBlock.getType()).isEqualTo("shell");
            assertThat(savedBlock.getConfig()).containsEntry("command", "echo hello");
            assertThat(savedBlock.getRequiredCapabilities()).containsExactly("filesystem");
            assertThat(savedBlock.getSkillIds()).containsExactly("skill-1");
        });
    }

    @Test
    void should_allowNullOptionalFields_when_constructedWithMinimalValues() {
        BuiltInTemplateDefinition definition = new BuiltInTemplateDefinition(
                "template-2",
                null,
                null,
                null,
                null,
                0,
                null,
                null,
                null,
                null
        );

        assertThat(definition.id()).isEqualTo("template-2");
        assertThat(definition.icon()).isNull();
        assertThat(definition.name()).isNull();
        assertThat(definition.description()).isNull();
        assertThat(definition.category()).isNull();
        assertThat(definition.sortOrder()).isZero();
        assertThat(definition.version()).isNull();
        assertThat(definition.model()).isNull();
        assertThat(definition.instructions()).isNull();
        assertThat(definition.blocks()).isNull();
    }
}
