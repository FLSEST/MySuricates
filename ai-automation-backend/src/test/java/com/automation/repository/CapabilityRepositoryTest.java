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
class CapabilityRepositoryTest {

    @Autowired
    private CapabilityRepository capabilityRepository;

    @Autowired
    private SkillRepository skillRepository;

    private Skill persistSkill(String name) {
        Skill skill = Skill.builder()
                .name(name)
                .description("Test skill " + name)
                .category(SkillCategory.CODE)
                .build();
        return skillRepository.saveAndFlush(skill);
    }

    private Capability persistCapability(Skill skill, String name) {
        Capability capability = Capability.builder()
                .skill(skill)
                .name(name)
                .description("Test capability " + name)
                .handlerClass("com.example.Handler")
                .async(false)
                .build();
        return capabilityRepository.saveAndFlush(capability);
    }

    // --- findBySkillId ---

    @Test
    void findBySkillId_returnsAllCapabilitiesForGivenSkill() {
        Skill skillA = persistSkill("skill-A-" + System.nanoTime());
        Skill skillB = persistSkill("skill-B-" + System.nanoTime());

        persistCapability(skillA, "cap-1");
        persistCapability(skillA, "cap-2");
        persistCapability(skillB, "cap-3");

        List<Capability> result = capabilityRepository.findBySkillId(skillA.getId());

        assertThat(result)
                .hasSize(2)
                .extracting(Capability::getName)
                .containsExactlyInAnyOrder("cap-1", "cap-2");
    }

    @Test
    void findBySkillId_returnsEmptyListWhenSkillHasNoCapabilities() {
        Skill skill = persistSkill("empty-skill-" + System.nanoTime());

        List<Capability> result = capabilityRepository.findBySkillId(skill.getId());

        assertThat(result).isEmpty();
    }

    @Test
    void findBySkillId_returnsEmptyListForUnknownSkillId() {
        List<Capability> result = capabilityRepository.findBySkillId("non-existent-skill-id");

        assertThat(result).isEmpty();
    }

    // --- findBySkillIdAndName ---

    @Test
    void findBySkillIdAndName_returnsCapabilityWhenSkillIdAndNameMatch() {
        Skill skill = persistSkill("skill-match-" + System.nanoTime());
        Capability persisted = persistCapability(skill, "specific-capability");

        Optional<Capability> result = capabilityRepository.findBySkillIdAndName(skill.getId(), "specific-capability");

        assertThat(result).isPresent();
        assertThat(result.get().getId()).isEqualTo(persisted.getId());
        assertThat(result.get().getName()).isEqualTo("specific-capability");
        assertThat(result.get().getSkill().getId()).isEqualTo(skill.getId());
    }

    @Test
    void findBySkillIdAndName_returnsEmptyWhenNameDoesNotMatch() {
        Skill skill = persistSkill("skill-name-mismatch-" + System.nanoTime());
        persistCapability(skill, "existing-cap");

        Optional<Capability> result = capabilityRepository.findBySkillIdAndName(skill.getId(), "nonexistent-name");

        assertThat(result).isEmpty();
    }

    @Test
    void findBySkillIdAndName_returnsEmptyWhenSkillIdDoesNotMatch() {
        Skill skillA = persistSkill("skill-A-" + System.nanoTime());
        Skill skillB = persistSkill("skill-B-" + System.nanoTime());
        persistCapability(skillA, "shared-name");

        Optional<Capability> result = capabilityRepository.findBySkillIdAndName(skillB.getId(), "shared-name");

        assertThat(result).isEmpty();
    }

    @Test
    void findBySkillIdAndName_distinguishesCapabilitiesAcrossSkillsWithSameName() {
        Skill skillA = persistSkill("skill-A-" + System.nanoTime());
        Skill skillB = persistSkill("skill-B-" + System.nanoTime());
        Capability capA = persistCapability(skillA, "common-name");
        Capability capB = persistCapability(skillB, "common-name");

        Optional<Capability> resultA = capabilityRepository.findBySkillIdAndName(skillA.getId(), "common-name");
        Optional<Capability> resultB = capabilityRepository.findBySkillIdAndName(skillB.getId(), "common-name");

        assertThat(resultA).isPresent();
        assertThat(resultA.get().getId()).isEqualTo(capA.getId());
        assertThat(resultB).isPresent();
        assertThat(resultB.get().getId()).isEqualTo(capB.getId());
    }
}
