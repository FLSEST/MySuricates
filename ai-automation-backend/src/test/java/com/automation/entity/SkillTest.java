package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SkillTest {

    @Test
    void should_create_skill_with_builder_defaults() {
        Skill skill = Skill.builder()
                .name("Test Skill")
                .category(firstCategory())
                .build();

        assertThat(skill.getName()).isEqualTo("Test Skill");
        assertThat(skill.getCategory()).isEqualTo(firstCategory());
        assertThat(skill.getConfigSchema()).isNotNull().isEmpty();
        assertThat(skill.isBuiltin()).isFalse();
        assertThat(skill.getVersion()).isEqualTo("1.0.0");
        assertThat(skill.getCapabilities()).isNotNull().isEmpty();
        assertThat(skill.getWorkflows()).isNotNull().isEmpty();
        assertThat(skill.getId()).isNull();
        assertThat(skill.getDescription()).isNull();
        assertThat(skill.getIcon()).isNull();
        assertThat(skill.getAiContext()).isNull();
    }

    @Test
    void should_create_skill_with_all_fields_via_builder() {
        Map<String, Object> schema = new HashMap<>();
        schema.put("key", "value");
        Capability cap = new Capability();
        Workflow wf = new Workflow();
        Instant now = Instant.now();

        Skill skill = Skill.builder()
                .id("skill-1")
                .name("Translator")
                .description("Translates text")
                .icon("icon.png")
                .category(firstCategory())
                .aiContext("Use this for translation")
                .configSchema(schema)
                .builtin(true)
                .version("2.1.0")
                .capabilities(List.of(cap))
                .workflows(List.of(wf))
                .createdAt(now)
                .updatedAt(now)
                .build();

        assertThat(skill.getId()).isEqualTo("skill-1");
        assertThat(skill.getName()).isEqualTo("Translator");
        assertThat(skill.getDescription()).isEqualTo("Translates text");
        assertThat(skill.getIcon()).isEqualTo("icon.png");
        assertThat(skill.getCategory()).isEqualTo(firstCategory());
        assertThat(skill.getAiContext()).isEqualTo("Use this for translation");
        assertThat(skill.getConfigSchema()).containsEntry("key", "value");
        assertThat(skill.isBuiltin()).isTrue();
        assertThat(skill.getVersion()).isEqualTo("2.1.0");
        assertThat(skill.getCapabilities()).containsExactly(cap);
        assertThat(skill.getWorkflows()).containsExactly(wf);
        assertThat(skill.getCreatedAt()).isEqualTo(now);
        assertThat(skill.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_use_no_args_constructor_with_null_defaults() {
        Skill skill = new Skill();

        assertThat(skill.getId()).isNull();
        assertThat(skill.getName()).isNull();
        assertThat(skill.getCategory()).isNull();
        // Felder ohne @Builder.Default werden vom NoArgsConstructor nicht initialisiert
        assertThat(skill.isBuiltin()).isFalse();
    }

    @Test
    void should_set_and_get_all_fields_via_setters() {
        Skill skill = new Skill();
        Map<String, Object> schema = new HashMap<>();
        schema.put("a", 1);
        Instant now = Instant.now();

        skill.setId("id-2");
        skill.setName("Name");
        skill.setDescription("Desc");
        skill.setIcon("icon");
        skill.setCategory(firstCategory());
        skill.setAiContext("ctx");
        skill.setConfigSchema(schema);
        skill.setBuiltin(true);
        skill.setVersion("3.0.0");
        skill.setCapabilities(List.of());
        skill.setWorkflows(List.of());
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);

        assertThat(skill.getId()).isEqualTo("id-2");
        assertThat(skill.getName()).isEqualTo("Name");
        assertThat(skill.getDescription()).isEqualTo("Desc");
        assertThat(skill.getIcon()).isEqualTo("icon");
        assertThat(skill.getCategory()).isEqualTo(firstCategory());
        assertThat(skill.getAiContext()).isEqualTo("ctx");
        assertThat(skill.getConfigSchema()).containsEntry("a", 1);
        assertThat(skill.isBuiltin()).isTrue();
        assertThat(skill.getVersion()).isEqualTo("3.0.0");
        assertThat(skill.getCapabilities()).isEmpty();
        assertThat(skill.getWorkflows()).isEmpty();
        assertThat(skill.getCreatedAt()).isEqualTo(now);
        assertThat(skill.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_assign_uuid_when_id_is_null_via_prePersist() throws Exception {
        Skill skill = Skill.builder().name("X").category(firstCategory()).build();
        assertThat(skill.getId()).isNull();

        invokeEnsureId(skill);

        assertThat(skill.getId()).isNotNull();
        // Verify it is a valid UUID
        UUID parsed = UUID.fromString(skill.getId());
        assertThat(parsed).isNotNull();
    }

    @Test
    void should_assign_uuid_when_id_is_blank_via_prePersist() throws Exception {
        Skill skill = Skill.builder().id("   ").name("X").category(firstCategory()).build();

        invokeEnsureId(skill);

        assertThat(skill.getId()).isNotBlank();
        UUID.fromString(skill.getId());
    }

    @Test
    void should_keep_existing_id_when_already_set_via_prePersist() throws Exception {
        Skill skill = Skill.builder().id("preset-id").name("X").category(firstCategory()).build();

        invokeEnsureId(skill);

        assertThat(skill.getId()).isEqualTo("preset-id");
    }

    @Test
    void should_create_skill_with_all_args_constructor() {
        Map<String, Object> schema = new HashMap<>();
        Skill skill = new Skill(
                "id-3",
                "AllArgs",
                "desc",
                "icon",
                firstCategory(),
                "ctx",
                schema,
                true,
                "1.2.3",
                List.of(),
                List.of(),
                Instant.now(),
                Instant.now()
        );

        assertThat(skill.getId()).isEqualTo("id-3");
        assertThat(skill.getName()).isEqualTo("AllArgs");
        assertThat(skill.getCategory()).isEqualTo(firstCategory());
        assertThat(skill.isBuiltin()).isTrue();
        assertThat(skill.getVersion()).isEqualTo("1.2.3");
    }

    private SkillCategory firstCategory() {
        return SkillCategory.values()[0];
    }

    private void invokeEnsureId(Skill skill) throws Exception {
        var method = Skill.class.getDeclaredMethod("ensureId");
        method.setAccessible(true);
        method.invoke(skill);
    }
}
