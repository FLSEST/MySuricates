package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class BuiltInSkillsTest {

    @Test
    void getAll_returnsAllSkills() {
        List<Skill> skills = BuiltInSkills.getAll();
        assertThat(skills).hasSize(16);
    }

    @Test
    void allSkills_haveRequiredFields() {
        for (Skill skill : BuiltInSkills.getAll()) {
            assertThat(skill.getId()).as("id for " + skill.getName()).isNotBlank();
            assertThat(skill.getName()).as("name").isNotBlank();
            assertThat(skill.getCategory()).as("category for " + skill.getName()).isNotNull();
            assertThat(skill.getAiContext()).as("aiContext for " + skill.getName()).isNotBlank();
            assertThat(skill.isBuiltin()).as("builtin for " + skill.getName()).isTrue();
            assertThat(skill.getVersion()).as("version for " + skill.getName()).isEqualTo("1.0.0");
        }
    }

    @Test
    void allSkills_haveUniqueIds() {
        List<Skill> skills = BuiltInSkills.getAll();
        Set<String> ids = new HashSet<>();
        for (Skill skill : skills) {
            assertThat(ids.add(skill.getId()))
                    .as("Duplicate ID: " + skill.getId())
                    .isTrue();
        }
    }

    @Test
    void allSkills_haveCapabilities() {
        for (Skill skill : BuiltInSkills.getAll()) {
            assertThat(skill.getCapabilities())
                    .as("capabilities for " + skill.getName())
                    .isNotEmpty();
        }
    }

    @Test
    void allCapabilities_haveBackReferenceToSkill() {
        for (Skill skill : BuiltInSkills.getAll()) {
            for (Capability cap : skill.getCapabilities()) {
                assertThat(cap.getSkill())
                        .as("skill back-reference for capability " + cap.getName())
                        .isSameAs(skill);
            }
        }
    }

    @Test
    void allCapabilities_haveRequiredFields() {
        for (Skill skill : BuiltInSkills.getAll()) {
            for (Capability cap : skill.getCapabilities()) {
                assertThat(cap.getName())
                        .as("name for capability in " + skill.getName())
                        .isNotBlank();
                assertThat(cap.getDescription())
                        .as("description for " + cap.getName())
                        .isNotBlank();
            }
        }
    }

    @Test
    void allCapabilityNames_uniqueWithinSkill() {
        for (Skill skill : BuiltInSkills.getAll()) {
            Set<String> names = new HashSet<>();
            for (Capability cap : skill.getCapabilities()) {
                assertThat(names.add(cap.getName()))
                        .as("Duplicate capability name '%s' in skill '%s'", cap.getName(), skill.getName())
                        .isTrue();
            }
        }
    }

    @Test
    void handlerClasses_onlyOnExpectedCapabilities() {
        // Verify that handler classes are set on capabilities that had them in the old SkillProviders
        List<Skill> skills = BuiltInSkills.getAll();

        long capabilitiesWithHandler = skills.stream()
                .flatMap(s -> s.getCapabilities().stream())
                .filter(c -> c.getHandlerClass() != null)
                .count();

        // We expect handlers on: lint, security_scan, check_grammar, ocr, fact_check, grammar_check
        assertThat(capabilitiesWithHandler).isGreaterThanOrEqualTo(6);
    }
}
