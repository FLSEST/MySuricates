package com.automation.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInSkillDefinitionTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void should_ignore_unknown_fields_on_skill_and_capability() throws Exception {
        String json = """
                {"id":"builtin-x","sortOrder":3,"version":"1.2.0","name":"X","icon":"i",
                 "description":"d","category":"CODE","futureField":42,
                 "capabilities":[{"name":"run","description":"c","handlerClass":"com.x.H",
                   "async":true,"writes":true,"somethingNew":"ignored",
                   "inputSchema":{"type":"object"},"outputSchema":{"type":"string"}}]}
                """;

        BuiltInSkillDefinition def = mapper.readValue(json, BuiltInSkillDefinition.class);

        assertThat(def.id()).isEqualTo("builtin-x");
        assertThat(def.sortOrder()).isEqualTo(3);
        assertThat(def.version()).isEqualTo("1.2.0");
        assertThat(def.category()).isEqualTo("CODE");
        assertThat(def.capabilities()).singleElement().satisfies(cap -> {
            assertThat(cap.name()).isEqualTo("run");
            assertThat(cap.handlerClass()).isEqualTo("com.x.H");
            assertThat(cap.async()).isTrue();
            assertThat(cap.writes()).isTrue();
            assertThat(cap.inputSchema()).containsEntry("type", "object");
            assertThat(cap.outputSchema()).containsEntry("type", "string");
        });
    }

    @Test
    void should_leave_missing_sort_order_null_instead_of_zero() throws Exception {
        // Integer on purpose: a missing mandatory field must be detectable, not silently 0
        BuiltInSkillDefinition def = mapper.readValue("{\"id\":\"builtin-y\"}", BuiltInSkillDefinition.class);

        assertThat(def.sortOrder()).isNull();
        assertThat(def.capabilities()).isNull();
    }

    @Test
    void should_default_capability_flags_to_false_so_capabilities_stay_read_only() throws Exception {
        BuiltInSkillDefinition def = mapper.readValue(
                "{\"id\":\"z\",\"capabilities\":[{\"name\":\"read\"}]}", BuiltInSkillDefinition.class);

        assertThat(def.capabilities()).singleElement().satisfies(cap -> {
            assertThat(cap.async()).isFalse();
            assertThat(cap.writes()).isFalse();
            assertThat(cap.inputSchema()).isNull();
        });
    }

    @Test
    void should_not_carry_the_prompt_text_which_lives_in_the_sibling_md() {
        assertThat(BuiltInSkillDefinition.class.getRecordComponents())
                .extracting(c -> c.getName())
                .doesNotContain("prompt", "promptTemplate", "content");
    }

    @Test
    void should_read_a_shipped_builtin_skill_file() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/skills/builtin/builtin-code-review.json")) {
            assertThat(in).isNotNull();
            BuiltInSkillDefinition def = mapper.readValue(in, BuiltInSkillDefinition.class);

            assertThat(def.id()).isEqualTo("builtin-code-review");
            assertThat(def.sortOrder()).isNotNull();
            assertThat(def.capabilities()).extracting(BuiltInSkillDefinition.CapabilityDefinition::name)
                    .contains("review_code");
        }
    }
}
