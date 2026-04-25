package com.automation.service.chain;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInChainTemplateTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // --- Record accessors ---

    @Test
    void should_expose_all_fields_when_constructed() {
        Map<String, String> name = Map.of("en", "My Chain", "de", "Meine Kette");
        Map<String, String> description = Map.of("en", "A chain", "de", "Eine Kette");
        BuiltInChainTemplate.Entry entry = new BuiltInChainTemplate.Entry(
                "tpl-1",
                Map.of("en", "Step 1", "de", "Schritt 1")
        );

        BuiltInChainTemplate template = new BuiltInChainTemplate(
                "chain-1",
                name,
                description,
                "icon-name",
                List.of(entry)
        );

        assertThat(template.id()).isEqualTo("chain-1");
        assertThat(template.name()).isEqualTo(name);
        assertThat(template.description()).isEqualTo(description);
        assertThat(template.icon()).isEqualTo("icon-name");
        assertThat(template.entries()).containsExactly(entry);
    }

    @Test
    void should_allow_null_values_when_constructed_with_nulls() {
        BuiltInChainTemplate template = new BuiltInChainTemplate(
                null, null, null, null, null
        );

        assertThat(template.id()).isNull();
        assertThat(template.name()).isNull();
        assertThat(template.description()).isNull();
        assertThat(template.icon()).isNull();
        assertThat(template.entries()).isNull();
    }

    @Test
    void should_be_equal_when_fields_match() {
        Map<String, String> name = Map.of("en", "Chain");
        Map<String, String> description = Map.of("en", "Desc");
        List<BuiltInChainTemplate.Entry> entries = List.of(
                new BuiltInChainTemplate.Entry("tpl-1", Map.of("en", "Label"))
        );

        BuiltInChainTemplate first = new BuiltInChainTemplate("id", name, description, "icon", entries);
        BuiltInChainTemplate second = new BuiltInChainTemplate("id", name, description, "icon", entries);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }

    @Test
    void should_not_be_equal_when_id_differs() {
        Map<String, String> name = Map.of("en", "Chain");

        BuiltInChainTemplate first = new BuiltInChainTemplate("id-a", name, name, "icon", List.of());
        BuiltInChainTemplate second = new BuiltInChainTemplate("id-b", name, name, "icon", List.of());

        assertThat(first).isNotEqualTo(second);
    }

    // --- JSON deserialization ---

    @Test
    void should_deserialize_from_json() throws Exception {
        String json = """
                {
                  "id": "chain-1",
                  "name": {"en": "My Chain", "de": "Meine Kette"},
                  "description": {"en": "Desc", "de": "Beschreibung"},
                  "icon": "icon-name",
                  "entries": [
                    {"templateId": "tpl-1", "label": {"en": "Step 1"}}
                  ]
                }
                """;

        BuiltInChainTemplate template = objectMapper.readValue(json, BuiltInChainTemplate.class);

        assertThat(template.id()).isEqualTo("chain-1");
        assertThat(template.name()).containsEntry("en", "My Chain").containsEntry("de", "Meine Kette");
        assertThat(template.description()).containsEntry("en", "Desc");
        assertThat(template.icon()).isEqualTo("icon-name");
        assertThat(template.entries()).hasSize(1);
        assertThat(template.entries().get(0).templateId()).isEqualTo("tpl-1");
        assertThat(template.entries().get(0).label()).containsEntry("en", "Step 1");
    }

    // --- JSON serialization ---

    @Test
    void should_serialize_to_json() throws Exception {
        BuiltInChainTemplate template = new BuiltInChainTemplate(
                "chain-1",
                Map.of("en", "My Chain"),
                Map.of("en", "Desc"),
                "icon-name",
                List.of(new BuiltInChainTemplate.Entry("tpl-1", Map.of("en", "Step 1")))
        );

        String json = objectMapper.writeValueAsString(template);

        assertThat(json).contains("\"id\":\"chain-1\"");
        assertThat(json).contains("\"icon\":\"icon-name\"");
        assertThat(json).contains("\"templateId\":\"tpl-1\"");
    }

    // --- JSON roundtrip ---

    @Test
    void should_survive_json_roundtrip() throws Exception {
        BuiltInChainTemplate original = new BuiltInChainTemplate(
                "chain-1",
                Map.of("en", "Chain", "de", "Kette"),
                Map.of("en", "Desc"),
                "icon",
                List.of(
                        new BuiltInChainTemplate.Entry("tpl-1", Map.of("en", "Step 1")),
                        new BuiltInChainTemplate.Entry("tpl-2", Map.of("en", "Step 2"))
                )
        );

        String json = objectMapper.writeValueAsString(original);
        BuiltInChainTemplate restored = objectMapper.readValue(json, BuiltInChainTemplate.class);

        assertThat(restored).isEqualTo(original);
    }

    // --- Nested Entry record ---

    @Test
    void should_expose_entry_fields_when_constructed() {
        Map<String, String> label = Map.of("en", "Step", "de", "Schritt");

        BuiltInChainTemplate.Entry entry = new BuiltInChainTemplate.Entry("tpl-42", label);

        assertThat(entry.templateId()).isEqualTo("tpl-42");
        assertThat(entry.label()).isEqualTo(label);
    }

    @Test
    void should_allow_null_values_in_entry() {
        BuiltInChainTemplate.Entry entry = new BuiltInChainTemplate.Entry(null, null);

        assertThat(entry.templateId()).isNull();
        assertThat(entry.label()).isNull();
    }

    @Test
    void should_be_equal_when_entry_fields_match() {
        Map<String, String> label = Map.of("en", "Label");

        BuiltInChainTemplate.Entry first = new BuiltInChainTemplate.Entry("tpl-1", label);
        BuiltInChainTemplate.Entry second = new BuiltInChainTemplate.Entry("tpl-1", label);

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
    }
}
