package com.automation.mapper;

import com.automation.dto.request.CreateSkillRequest;
import com.automation.dto.response.CapabilityResponse;
import com.automation.dto.response.SkillResponse;
import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SkillMapperTest {

    private SkillMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new SkillMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            Skill skill = Skill.builder()
                    .id("sk-1")
                    .name("Test Skill")
                    .description("A test skill")
                    .icon("🔧")
                    .category(SkillCategory.CODE)
                    .aiContext("some AI context")
                    .configSchema(Map.of("key", "value"))
                    .builtin(true)
                    .version("2.0.0")
                    .capabilities(List.of())
                    .createdAt(now)
                    .build();

            SkillResponse response = mapper.toResponse(skill);

            assertThat(response.id()).isEqualTo("sk-1");
            assertThat(response.name()).isEqualTo("Test Skill");
            assertThat(response.description()).isEqualTo("A test skill");
            assertThat(response.icon()).isEqualTo("🔧");
            assertThat(response.category()).isEqualTo(SkillCategory.CODE);
            assertThat(response.aiContext()).isEqualTo("some AI context");
            assertThat(response.configSchema()).containsEntry("key", "value");
            assertThat(response.builtin()).isTrue();
            assertThat(response.version()).isEqualTo("2.0.0");
            assertThat(response.capabilities()).isEmpty();
            assertThat(response.createdAt()).isEqualTo(now);
        }

        @Test
        void mapsCapabilitiesList() {
            Capability cap = Capability.builder()
                    .id("cap-1")
                    .name("Do Something")
                    .description("does something")
                    .inputSchema(Map.of("input", "schema"))
                    .outputSchema(Map.of("output", "schema"))
                    .async(true)
                    .build();

            Skill skill = Skill.builder()
                    .id("sk-1")
                    .name("Skill")
                    .category(SkillCategory.DATA)
                    .capabilities(List.of(cap))
                    .build();

            SkillResponse response = mapper.toResponse(skill);

            assertThat(response.capabilities()).hasSize(1);
            assertThat(response.capabilities().get(0).id()).isEqualTo("cap-1");
            assertThat(response.capabilities().get(0).name()).isEqualTo("Do Something");
            assertThat(response.capabilities().get(0).async()).isTrue();
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            Skill skill = Skill.builder()
                    .id("sk-1")
                    .name("Minimal Skill")
                    .category(SkillCategory.DOCUMENT)
                    .capabilities(List.of())
                    .build();

            SkillResponse response = mapper.toResponse(skill);

            assertThat(response.description()).isNull();
            assertThat(response.icon()).isNull();
            assertThat(response.aiContext()).isNull();
            assertThat(response.createdAt()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        Skill s1 = Skill.builder().id("sk-1").name("A").category(SkillCategory.CODE).capabilities(List.of()).build();
        Skill s2 = Skill.builder().id("sk-2").name("B").category(SkillCategory.DATA).capabilities(List.of()).build();

        List<SkillResponse> responses = mapper.toResponseList(List.of(s1, s2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("sk-1");
        assertThat(responses.get(1).id()).isEqualTo("sk-2");
    }

    @Nested
    class ToCapabilityResponse {

        @Test
        void mapsAllFields() {
            Capability cap = Capability.builder()
                    .id("cap-1")
                    .name("My Capability")
                    .description("cap description")
                    .inputSchema(Map.of("in", "schema"))
                    .outputSchema(Map.of("out", "schema"))
                    .async(false)
                    .build();

            CapabilityResponse response = mapper.toCapabilityResponse(cap);

            assertThat(response.id()).isEqualTo("cap-1");
            assertThat(response.name()).isEqualTo("My Capability");
            assertThat(response.description()).isEqualTo("cap description");
            assertThat(response.inputSchema()).containsEntry("in", "schema");
            assertThat(response.outputSchema()).containsEntry("out", "schema");
            assertThat(response.async()).isFalse();
        }

        @Test
        void asyncTrue_mappedCorrectly() {
            Capability cap = Capability.builder()
                    .id("cap-2")
                    .name("Async Cap")
                    .async(true)
                    .build();

            CapabilityResponse response = mapper.toCapabilityResponse(cap);

            assertThat(response.async()).isTrue();
        }
    }

    @Test
    void toCapabilityResponseList_mapsMultiple() {
        Capability c1 = Capability.builder().id("c1").name("Cap A").build();
        Capability c2 = Capability.builder().id("c2").name("Cap B").build();

        List<CapabilityResponse> responses = mapper.toCapabilityResponseList(List.of(c1, c2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).name()).isEqualTo("Cap A");
        assertThat(responses.get(1).name()).isEqualTo("Cap B");
    }

    @Nested
    class ToEntity {

        @Test
        void mapsRequestFields() {
            CreateSkillRequest request = new CreateSkillRequest(
                    "My Skill",
                    "A skill description",
                    "🚀",
                    SkillCategory.RESEARCH,
                    "AI context text",
                    Map.of("param", "value")
            );

            Skill skill = mapper.toEntity(request);

            assertThat(skill.getName()).isEqualTo("My Skill");
            assertThat(skill.getDescription()).isEqualTo("A skill description");
            assertThat(skill.getIcon()).isEqualTo("🚀");
            assertThat(skill.getCategory()).isEqualTo(SkillCategory.RESEARCH);
            assertThat(skill.getAiContext()).isEqualTo("AI context text");
            assertThat(skill.getConfigSchema()).containsEntry("param", "value");
        }

        @Test
        void constantMappings_appliedCorrectly() {
            CreateSkillRequest request = new CreateSkillRequest(
                    "Skill",
                    null,
                    null,
                    SkillCategory.BUSINESS,
                    null,
                    null
            );

            Skill skill = mapper.toEntity(request);

            assertThat(skill.isBuiltin()).isFalse();
            assertThat(skill.getVersion()).isEqualTo("1.0.0");
        }

        @Test
        void ignoredFields_notPopulatedFromRequest() {
            CreateSkillRequest request = new CreateSkillRequest(
                    "Skill",
                    null,
                    null,
                    SkillCategory.PRODUCTIVITY,
                    null,
                    null
            );

            Skill skill = mapper.toEntity(request);

            assertThat(skill.getId()).isNull();
            // capabilities and workflows have @Builder.Default → empty lists, not null
            assertThat(skill.getCapabilities()).isEmpty();
            assertThat(skill.getWorkflows()).isEmpty();
            assertThat(skill.getCreatedAt()).isNull();
        }
    }
}
