package com.automation.repository;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class SkillRepositoryTest {

    @Autowired
    private SkillRepository repository;

    private Skill saveSkillWithCapabilities(String name, SkillCategory category, String... capabilityNames) {
        Skill skill = Skill.builder()
                .name(name)
                .description("desc-" + name)
                .category(category)
                .build();
        for (String capName : capabilityNames) {
            skill.getCapabilities().add(Capability.builder()
                    .skill(skill)
                    .name(capName)
                    .description("cap-desc-" + capName)
                    .build());
        }
        return repository.save(skill);
    }

    // --- findAllWithCapabilities ---

    @Test
    void findAllWithCapabilities_returnsAllSkillsWithCapabilitiesAccessible() {
        saveSkillWithCapabilities("Skill A", SkillCategory.RESEARCH, "cap-a1", "cap-a2");
        saveSkillWithCapabilities("Skill B", SkillCategory.PRODUCTIVITY, "cap-b1");

        List<Skill> result = repository.findAllWithCapabilities();

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        Skill skillA = result.stream().filter(s -> "Skill A".equals(s.getName())).findFirst().orElseThrow();
        assertThat(skillA.getCapabilities()).hasSize(2);
        assertThat(skillA.getCapabilities()).extracting(Capability::getName)
                .containsExactlyInAnyOrder("cap-a1", "cap-a2");
    }

    @Test
    void findAllWithCapabilities_returnsEmptyCapabilitiesForSkillsWithoutCapabilities() {
        saveSkillWithCapabilities("Lonely", SkillCategory.PRODUCTIVITY);

        List<Skill> result = repository.findAllWithCapabilities();

        Skill lonely = result.stream().filter(s -> "Lonely".equals(s.getName())).findFirst().orElseThrow();
        assertThat(lonely.getCapabilities()).isEmpty();
    }

    @Test
    void findAllWithCapabilities_ordersByCategoryThenName() {
        saveSkillWithCapabilities("Zebra Prod", SkillCategory.PRODUCTIVITY);
        saveSkillWithCapabilities("Alpha Prod", SkillCategory.PRODUCTIVITY);
        saveSkillWithCapabilities("Beta Research", SkillCategory.RESEARCH);

        List<Skill> result = repository.findAllWithCapabilities();

        List<String> namesInResult = result.stream()
                .filter(s -> List.of("Zebra Prod", "Alpha Prod", "Beta Research").contains(s.getName()))
                .map(Skill::getName)
                .toList();
        assertThat(namesInResult.indexOf("Alpha Prod")).isLessThan(namesInResult.indexOf("Zebra Prod"));
    }

    // --- findByIdWithCapabilities ---

    @Test
    void findByIdWithCapabilities_returnsSkillWithCapabilitiesLoaded() {
        Skill saved = saveSkillWithCapabilities("Detail Skill", SkillCategory.RESEARCH, "cap-1", "cap-2", "cap-3");

        Optional<Skill> result = repository.findByIdWithCapabilities(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getName()).isEqualTo("Detail Skill");
        assertThat(result.get().getCapabilities()).hasSize(3);
    }

    @Test
    void findByIdWithCapabilities_returnsEmptyForUnknownId() {
        Optional<Skill> result = repository.findByIdWithCapabilities("no-such-id");

        assertThat(result).isEmpty();
    }

    @Test
    void findByIdWithCapabilities_returnsSkillWithoutCapabilitiesWhenNoneExist() {
        Skill saved = saveSkillWithCapabilities("Empty", SkillCategory.PRODUCTIVITY);

        Optional<Skill> result = repository.findByIdWithCapabilities(saved.getId());

        assertThat(result).isPresent();
        assertThat(result.get().getCapabilities()).isEmpty();
    }
}
