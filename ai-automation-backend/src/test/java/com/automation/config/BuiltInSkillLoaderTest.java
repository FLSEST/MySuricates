package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class BuiltInSkillLoaderTest {

    private static List<Skill> skills;

    @BeforeAll
    static void load() {
        skills = new BuiltInSkillLoader(new ObjectMapper()).loadAll();
    }

    @Test
    void loadAll_returnsAllNineteenBuiltInSkills() {
        assertThat(skills).hasSize(26);
    }

    @Test
    void allSkills_haveStableBuiltinIds() {
        for (Skill skill : skills) {
            assertThat(skill.getId()).as("id").startsWith("builtin-");
        }
        Set<String> ids = new HashSet<>();
        for (Skill skill : skills) {
            assertThat(ids.add(skill.getId())).as("Duplicate id: " + skill.getId()).isTrue();
        }
    }

    @Test
    void allSkills_haveRequiredFieldsAndVerbatimPrompt() {
        for (Skill skill : skills) {
            assertThat(skill.getName()).as("name for " + skill.getId()).isNotBlank();
            assertThat(skill.getIcon()).as("icon for " + skill.getId()).isNotBlank();
            assertThat(skill.getDescription()).as("description for " + skill.getId()).isNotBlank();
            assertThat(skill.getCategory()).as("category for " + skill.getId()).isNotNull();
            assertThat(skill.getVersion()).as("version for " + skill.getId()).isEqualTo("1.0.0");
            assertThat(skill.isBuiltin()).as("builtin for " + skill.getId()).isTrue();
            assertThat(skill.getAiContext()).as("prompt for " + skill.getId()).isNotBlank();
            // The prompt is taken over byte-exactly — no trim, no appended line break.
            assertThat(skill.getAiContext())
                    .as("prompt for " + skill.getId() + " darf nicht getrimmt/ergaenzt werden")
                    .doesNotEndWith("\n")
                    .isEqualTo(skill.getAiContext().stripTrailing());
        }
    }

    @Test
    void sortOrder_isUniqueAndGapless() {
        List<Integer> orders = new BuiltInSkillLoader(new ObjectMapper()).loadAllDefinitions()
                .stream().map(BuiltInSkillDefinition::sortOrder).toList();

        assertThat(orders).doesNotHaveDuplicates();
        assertThat(orders).isSorted();
        assertThat(orders).containsExactlyElementsOf(
                java.util.stream.IntStream.rangeClosed(1, orders.size()).boxed().toList());
    }

    @Test
    void capabilities_carryBackReferenceAndWriteFlag() {
        for (Skill skill : skills) {
            assertThat(skill.getCapabilities()).as("capabilities for " + skill.getId()).isNotEmpty();
            for (Capability cap : skill.getCapabilities()) {
                assertThat(cap.getSkill()).as("back-reference for " + cap.getName()).isSameAs(skill);
                assertThat(cap.getName()).as("capability name in " + skill.getId()).isNotBlank();
                assertThat(cap.getDescription()).as("description for " + cap.getName()).isNotBlank();
            }
        }

        List<String> writeCapabilities = skills.stream()
                .flatMap(s -> s.getCapabilities().stream())
                .filter(Capability::isWrites)
                .map(Capability::getName)
                .toList();

        assertThat(writeCapabilities).containsExactlyInAnyOrder(
                "generate_unit_tests",
                "generate_integration_tests",
                "implement_feature",
                "write_tests",
                "apply_routes_glue");
    }

    @Test
    void handlerClasses_surviveTheMove() {
        long withHandler = skills.stream()
                .flatMap(s -> s.getCapabilities().stream())
                .filter(c -> c.getHandlerClass() != null)
                .count();

        assertThat(withHandler).isGreaterThanOrEqualTo(6);
    }

    @Test
    void loadAll_failsWhenPromptFileIsMissing() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/missing-md");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("builtin-without-prompt.md");
    }

    @Test
    void loadAll_failsOnDuplicateSortOrder() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/duplicate-order");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("sortOrder");
    }

    @Test
    void loadAll_failsWhenFilenameDoesNotMatchId() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/id-mismatch");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("wrong-filename.json")
                .hasMessageContaining("builtin-bar.json");
    }

    @Test
    void loadAll_failsWhenSortOrderIsMissing() {
        BuiltInSkillLoader loader =
                new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/missing-sort-order");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("builtin-no-sort-order.json")
                .hasMessageContaining("sortOrder");
    }

    @Test
    void loadAll_failsOnUnknownCategory() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/bad-category");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("builtin-bad-category.json")
                .hasMessageContaining("NOT_A_CATEGORY");
    }

    @Test
    void loadAll_failsWhenNoResourcesExist() {
        BuiltInSkillLoader loader = new BuiltInSkillLoader(new ObjectMapper(), "skills/testcases/empty");

        assertThatThrownBy(loader::loadAll)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("skills/testcases/empty");
    }
}
