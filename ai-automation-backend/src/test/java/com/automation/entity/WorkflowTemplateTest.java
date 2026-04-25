package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowTemplateTest {

    @Test
    void should_create_with_no_args_constructor_and_have_default_field_values() {
        WorkflowTemplate template = new WorkflowTemplate();

        // Builder.Default values are NOT applied for NoArgsConstructor — only for builder.
        // For NoArgsConstructor, fields are at their Java defaults / field initializers.
        assertThat(template.getId()).isNull();
        assertThat(template.getName()).isNotNull().isEmpty();
        assertThat(template.getDescription()).isNotNull().isEmpty();
        assertThat(template.getBlocks()).isNotNull().isEmpty();
        assertThat(template.isBuiltIn()).isFalse();
        assertThat(template.getSortOrder()).isZero();
        assertThat(template.getQualityStatus()).isEqualTo("UNTESTED");
        assertThat(template.getVersion()).isEqualTo("1.0.0");
        assertThat(template.getIcon()).isNull();
        assertThat(template.getCategory()).isNull();
        assertThat(template.getInstructions()).isNull();
        assertThat(template.getModel()).isNull();
        assertThat(template.getCreatedAt()).isNull();
        assertThat(template.getUpdatedAt()).isNull();
    }

    @Test
    void should_apply_builder_defaults_when_using_builder_without_overrides() {
        WorkflowTemplate template = WorkflowTemplate.builder().build();

        assertThat(template.getName()).isNotNull().isEmpty();
        assertThat(template.getDescription()).isNotNull().isEmpty();
        assertThat(template.getBlocks()).isNotNull().isEmpty();
        assertThat(template.isBuiltIn()).isFalse();
        assertThat(template.getSortOrder()).isZero();
        assertThat(template.getQualityStatus()).isEqualTo("UNTESTED");
        assertThat(template.getVersion()).isEqualTo("1.0.0");
    }

    @Test
    void should_set_all_fields_when_using_builder_with_full_payload() {
        Map<String, String> name = new HashMap<>();
        name.put("de", "Pool-Neutrality-Audit");
        name.put("en", "Pool Neutrality Audit");

        Map<String, String> description = new HashMap<>();
        description.put("de", "Prüft die Pool-Neutralität");
        description.put("en", "Audits pool neutrality");

        BlockDefinition block = new BlockDefinition();
        List<BlockDefinition> blocks = new ArrayList<>();
        blocks.add(block);

        WorkflowTemplate template = WorkflowTemplate.builder()
                .id("template-1")
                .name(name)
                .description(description)
                .icon("audit-icon")
                .category("audit")
                .blocks(blocks)
                .instructions("Run carefully")
                .model("claude-opus-4-6")
                .builtIn(true)
                .sortOrder(42)
                .qualityStatus("VERIFIED")
                .version("2.0.0")
                .build();

        assertThat(template.getId()).isEqualTo("template-1");
        assertThat(template.getName()).containsEntry("de", "Pool-Neutrality-Audit");
        assertThat(template.getName()).containsEntry("en", "Pool Neutrality Audit");
        assertThat(template.getDescription()).containsEntry("de", "Prüft die Pool-Neutralität");
        assertThat(template.getDescription()).containsEntry("en", "Audits pool neutrality");
        assertThat(template.getIcon()).isEqualTo("audit-icon");
        assertThat(template.getCategory()).isEqualTo("audit");
        assertThat(template.getBlocks()).hasSize(1).containsExactly(block);
        assertThat(template.getInstructions()).isEqualTo("Run carefully");
        assertThat(template.getModel()).isEqualTo("claude-opus-4-6");
        assertThat(template.isBuiltIn()).isTrue();
        assertThat(template.getSortOrder()).isEqualTo(42);
        assertThat(template.getQualityStatus()).isEqualTo("VERIFIED");
        assertThat(template.getVersion()).isEqualTo("2.0.0");
    }

    @Test
    void should_create_with_all_args_constructor_and_set_every_field() {
        Instant now = Instant.now();
        Map<String, String> name = new HashMap<>();
        name.put("de", "Name");
        Map<String, String> description = new HashMap<>();
        description.put("de", "Beschreibung");
        List<BlockDefinition> blocks = new ArrayList<>();

        WorkflowTemplate template = new WorkflowTemplate(
                "id-123",
                name,
                description,
                "icon",
                "category",
                blocks,
                "instructions",
                "model",
                true,
                5,
                "TESTED",
                "3.0.0",
                now,
                now
        );

        assertThat(template.getId()).isEqualTo("id-123");
        assertThat(template.getName()).isSameAs(name);
        assertThat(template.getDescription()).isSameAs(description);
        assertThat(template.getIcon()).isEqualTo("icon");
        assertThat(template.getCategory()).isEqualTo("category");
        assertThat(template.getBlocks()).isSameAs(blocks);
        assertThat(template.getInstructions()).isEqualTo("instructions");
        assertThat(template.getModel()).isEqualTo("model");
        assertThat(template.isBuiltIn()).isTrue();
        assertThat(template.getSortOrder()).isEqualTo(5);
        assertThat(template.getQualityStatus()).isEqualTo("TESTED");
        assertThat(template.getVersion()).isEqualTo("3.0.0");
        assertThat(template.getCreatedAt()).isEqualTo(now);
        assertThat(template.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_round_trip_all_fields_via_setters_and_getters() {
        WorkflowTemplate template = new WorkflowTemplate();
        Instant now = Instant.now();

        Map<String, String> name = Map.of("de", "X");
        Map<String, String> description = Map.of("de", "Y");
        List<BlockDefinition> blocks = List.of(new BlockDefinition());

        template.setId("abc");
        template.setName(name);
        template.setDescription(description);
        template.setIcon("icon");
        template.setCategory("category");
        template.setBlocks(blocks);
        template.setInstructions("instr");
        template.setModel("claude-sonnet-4-6");
        template.setBuiltIn(true);
        template.setSortOrder(7);
        template.setQualityStatus("FAILED");
        template.setVersion("0.0.1");
        template.setCreatedAt(now);
        template.setUpdatedAt(now);

        assertThat(template.getId()).isEqualTo("abc");
        assertThat(template.getName()).isEqualTo(name);
        assertThat(template.getDescription()).isEqualTo(description);
        assertThat(template.getIcon()).isEqualTo("icon");
        assertThat(template.getCategory()).isEqualTo("category");
        assertThat(template.getBlocks()).isEqualTo(blocks);
        assertThat(template.getInstructions()).isEqualTo("instr");
        assertThat(template.getModel()).isEqualTo("claude-sonnet-4-6");
        assertThat(template.isBuiltIn()).isTrue();
        assertThat(template.getSortOrder()).isEqualTo(7);
        assertThat(template.getQualityStatus()).isEqualTo("FAILED");
        assertThat(template.getVersion()).isEqualTo("0.0.1");
        assertThat(template.getCreatedAt()).isEqualTo(now);
        assertThat(template.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_generate_uuid_when_ensureId_invoked_with_null_id() throws Exception {
        WorkflowTemplate template = WorkflowTemplate.builder().build();
        assertThat(template.getId()).isNull();

        invokeEnsureId(template);

        assertThat(template.getId()).isNotNull();
        // Should be a valid UUID string
        UUID.fromString(template.getId());
    }

    @Test
    void should_generate_uuid_when_ensureId_invoked_with_blank_id() throws Exception {
        WorkflowTemplate template = WorkflowTemplate.builder().id("   ").build();

        invokeEnsureId(template);

        assertThat(template.getId()).isNotBlank();
        UUID.fromString(template.getId());
    }

    @Test
    void should_keep_existing_id_when_ensureId_invoked_with_non_blank_id() throws Exception {
        WorkflowTemplate template = WorkflowTemplate.builder().id("preset-id").build();

        invokeEnsureId(template);

        assertThat(template.getId()).isEqualTo("preset-id");
    }

    @Test
    void should_treat_empty_string_id_as_blank_and_replace_with_uuid() throws Exception {
        WorkflowTemplate template = WorkflowTemplate.builder().id("").build();

        invokeEnsureId(template);

        assertThat(template.getId()).isNotBlank();
        UUID.fromString(template.getId());
    }

    @Test
    void should_allow_multiple_languages_in_name_map() {
        WorkflowTemplate template = WorkflowTemplate.builder().build();
        template.getName().put("de", "Deutsch");
        template.getName().put("en", "English");
        template.getName().put("fr", "Français");

        assertThat(template.getName()).hasSize(3);
        assertThat(template.getName()).containsKeys("de", "en", "fr");
    }

    @Test
    void should_allow_adding_blocks_after_construction() {
        WorkflowTemplate template = WorkflowTemplate.builder().build();
        BlockDefinition block1 = new BlockDefinition();
        BlockDefinition block2 = new BlockDefinition();

        template.getBlocks().add(block1);
        template.getBlocks().add(block2);

        assertThat(template.getBlocks()).hasSize(2).containsExactly(block1, block2);
    }

    @Test
    void should_have_independent_default_collections_per_builder_instance() {
        WorkflowTemplate t1 = WorkflowTemplate.builder().build();
        WorkflowTemplate t2 = WorkflowTemplate.builder().build();

        t1.getName().put("de", "Test1");
        t1.getBlocks().add(new BlockDefinition());

        // t2 should NOT be affected by t1's mutations
        assertThat(t2.getName()).isEmpty();
        assertThat(t2.getBlocks()).isEmpty();
    }

    private void invokeEnsureId(WorkflowTemplate template) throws Exception {
        Method m = WorkflowTemplate.class.getDeclaredMethod("ensureId");
        m.setAccessible(true);
        m.invoke(template);
    }
}
