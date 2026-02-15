package com.automation.service.skill;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SkillRegistryTest {

    @Mock
    private CapabilityRegistry capabilityRegistry;

    private SkillRegistry skillRegistry;

    private Skill testSkill;
    private Capability testCapability;

    @BeforeEach
    void setUp() {
        skillRegistry = new SkillRegistry(List.of(), capabilityRegistry);

        testCapability = Capability.builder()
                .id("cap-1")
                .name("ocr")
                .handlerClass("com.automation.skills.handlers.OCRHandler")
                .build();

        testSkill = Skill.builder()
                .id("skill-1")
                .name("Document Analyzer")
                .category(SkillCategory.DOCUMENT)
                .aiContext("You are a document analyzer.")
                .capabilities(List.of(testCapability))
                .build();
    }

    @Test
    void register_addsSkillAndCapabilities() {
        skillRegistry.register(testSkill);

        Optional<Skill> found = skillRegistry.findById("skill-1");
        assertThat(found).isPresent();
        assertThat(found.get().getName()).isEqualTo("Document Analyzer");
        verify(capabilityRegistry).register("skill-1", testCapability);
    }

    @Test
    void register_skillWithNullCapabilities_doesNotFail() {
        Skill skillNoCapabilities = Skill.builder()
                .id("skill-2")
                .name("No Caps")
                .category(SkillCategory.CODE)
                .build();
        skillNoCapabilities.setCapabilities(null);

        skillRegistry.register(skillNoCapabilities);

        assertThat(skillRegistry.findById("skill-2")).isPresent();
    }

    @Test
    void unregister_removesSkillAndCapabilities() {
        skillRegistry.register(testSkill);

        skillRegistry.unregister("skill-1");

        assertThat(skillRegistry.findById("skill-1")).isEmpty();
        verify(capabilityRegistry).unregister("skill-1", "ocr");
    }

    @Test
    void unregister_nonExistentSkill_doesNotFail() {
        skillRegistry.unregister("non-existent");
        // No exception thrown
    }

    @Test
    void getAllRegistered_returnsAllSkills() {
        skillRegistry.register(testSkill);

        Skill anotherSkill = Skill.builder()
                .id("skill-2")
                .name("Code Reviewer")
                .category(SkillCategory.CODE)
                .build();
        skillRegistry.register(anotherSkill);

        List<Skill> all = skillRegistry.getAllRegistered();
        assertThat(all).hasSize(2);
    }

    @Test
    void getAggregatedAIContext_combinesSkillContexts() {
        Skill skill1 = Skill.builder()
                .id("s1").name("Skill A").category(SkillCategory.CODE)
                .aiContext("Context A").build();
        Skill skill2 = Skill.builder()
                .id("s2").name("Skill B").category(SkillCategory.DOCUMENT)
                .aiContext("Context B").build();

        String context = skillRegistry.getAggregatedAIContext(List.of(skill1, skill2));

        assertThat(context).contains("=== Skill: Skill A ===");
        assertThat(context).contains("Context A");
        assertThat(context).contains("=== Skill: Skill B ===");
        assertThat(context).contains("Context B");
    }

    @Test
    void getAggregatedAIContext_skipsNullAndBlankContext() {
        Skill skillNoContext = Skill.builder()
                .id("s1").name("No Context").category(SkillCategory.CODE)
                .aiContext(null).build();
        Skill skillBlankContext = Skill.builder()
                .id("s2").name("Blank Context").category(SkillCategory.CODE)
                .aiContext("   ").build();
        Skill skillWithContext = Skill.builder()
                .id("s3").name("Has Context").category(SkillCategory.CODE)
                .aiContext("Valid context").build();

        String context = skillRegistry.getAggregatedAIContext(
                List.of(skillNoContext, skillBlankContext, skillWithContext));

        assertThat(context).doesNotContain("No Context");
        assertThat(context).doesNotContain("Blank Context");
        assertThat(context).contains("Valid context");
    }
}
