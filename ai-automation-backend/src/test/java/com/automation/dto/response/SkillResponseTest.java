package com.automation.dto.response;

import static org.assertj.core.api.Assertions.assertThat;

import com.automation.entity.SkillCategory;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SkillResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_expose_all_fields_when_constructed_with_full_values() {
        Instant createdAt = Instant.parse("2026-01-10T12:00:00Z");
        CapabilityResponse capability = new CapabilityResponse(
            "cap-1", "Summarize", "Summarizes text", Map.of(), Map.of(), false, false
        );
        Map<String, Object> schema = Map.of("maxTokens", 1024);

        SkillResponse response = new SkillResponse(
            "skill-abc",
            "Document Summarizer",
            "Summarizes documents",
            "doc-icon",
            SkillCategory.DOCUMENT,
            "You summarize documents.",
            schema,
            true,
            "1.0.0",
            List.of(capability),
            createdAt
        );

        assertThat(response.id()).isEqualTo("skill-abc");
        assertThat(response.name()).isEqualTo("Document Summarizer");
        assertThat(response.description()).isEqualTo("Summarizes documents");
        assertThat(response.icon()).isEqualTo("doc-icon");
        assertThat(response.category()).isEqualTo(SkillCategory.DOCUMENT);
        assertThat(response.aiContext()).isEqualTo("You summarize documents.");
        assertThat(response.configSchema()).isEqualTo(schema);
        assertThat(response.builtin()).isTrue();
        assertThat(response.version()).isEqualTo("1.0.0");
        assertThat(response.capabilities()).containsExactly(capability);
        assertThat(response.createdAt()).isEqualTo(createdAt);
    }

    @Test
    void should_allow_null_optional_fields_when_minimal_record() {
        SkillResponse response = new SkillResponse(
            "skill-min",
            "Minimal Skill",
            null,
            null,
            SkillCategory.CODE,
            null,
            null,
            false,
            null,
            null,
            null
        );

        assertThat(response.id()).isEqualTo("skill-min");
        assertThat(response.name()).isEqualTo("Minimal Skill");
        assertThat(response.description()).isNull();
        assertThat(response.icon()).isNull();
        assertThat(response.category()).isEqualTo(SkillCategory.CODE);
        assertThat(response.aiContext()).isNull();
        assertThat(response.configSchema()).isNull();
        assertThat(response.builtin()).isFalse();
        assertThat(response.version()).isNull();
        assertThat(response.capabilities()).isNull();
        assertThat(response.createdAt()).isNull();
    }

    @Test
    void should_handle_empty_collections_when_no_capabilities_or_schema() {
        SkillResponse response = new SkillResponse(
            "skill-empty",
            "Empty Skill",
            "desc",
            "icon",
            SkillCategory.DATA,
            "context",
            Map.of(),
            false,
            "2.0.0",
            List.of(),
            Instant.now()
        );

        assertThat(response.capabilities()).isNotNull().isEmpty();
        assertThat(response.configSchema()).isNotNull().isEmpty();
    }

    // --- Record equality ---

    @Test
    void should_be_equal_when_all_fields_match() {
        Instant ts = Instant.parse("2026-03-01T09:00:00Z");
        SkillResponse a = new SkillResponse(
            "id", "name", "desc", "icon", SkillCategory.RESEARCH,
            "ctx", Map.of(), true, "1.0", List.of(), ts
        );
        SkillResponse b = new SkillResponse(
            "id", "name", "desc", "icon", SkillCategory.RESEARCH,
            "ctx", Map.of(), true, "1.0", List.of(), ts
        );

        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
    }

    @Test
    void should_not_be_equal_when_id_differs() {
        SkillResponse a = new SkillResponse(
            "id-a", "name", null, null, SkillCategory.CONTENT,
            null, null, false, null, null, null
        );
        SkillResponse b = new SkillResponse(
            "id-b", "name", null, null, SkillCategory.CONTENT,
            null, null, false, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_builtin_differs() {
        SkillResponse a = new SkillResponse(
            "id", "name", null, null, SkillCategory.BUSINESS,
            null, null, true, null, null, null
        );
        SkillResponse b = new SkillResponse(
            "id", "name", null, null, SkillCategory.BUSINESS,
            null, null, false, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_not_be_equal_when_category_differs() {
        SkillResponse a = new SkillResponse(
            "id", "name", null, null, SkillCategory.CODE,
            null, null, false, null, null, null
        );
        SkillResponse b = new SkillResponse(
            "id", "name", null, null, SkillCategory.DATA,
            null, null, false, null, null, null
        );

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_include_field_names_in_toString() {
        SkillResponse response = new SkillResponse(
            "skill-ts", "TS Skill", null, null, SkillCategory.PRODUCTIVITY,
            null, null, true, "3.1.0", null, null
        );

        String repr = response.toString();
        assertThat(repr).contains("id=skill-ts");
        assertThat(repr).contains("name=TS Skill");
        assertThat(repr).contains("category=PRODUCTIVITY");
        assertThat(repr).contains("builtin=true");
        assertThat(repr).contains("version=3.1.0");
    }
}
