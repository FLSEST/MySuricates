package com.automation.service.assistant.context;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.repository.SkillRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillsIndexSectionTest {

    @Mock private SkillRepository skillRepository;

    private SkillsIndexSection section;

    @BeforeEach
    void setUp() {
        section = new SkillsIndexSection(skillRepository);
    }

    private Skill skill(String id, String name, SkillCategory category, String description, int capabilityCount) {
        List<Capability> caps = new ArrayList<>();
        for (int i = 0; i < capabilityCount; i++) {
            caps.add(Capability.builder().name("cap-" + i).build());
        }
        return Skill.builder()
                .id(id)
                .name(name)
                .category(category)
                .description(description)
                .capabilities(caps)
                .build();
    }

    @Test
    void compact_showsCount() {
        when(skillRepository.findAllWithCapabilities()).thenReturn(List.of(
                skill("s1", "Skill A", SkillCategory.RESEARCH, "desc A", 2),
                skill("s2", "Skill B", SkillCategory.CODE, "desc B", 1)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).contains("**Skills:** 2 verfügbar");
    }

    @Test
    void compact_noSkills_appendsNothing() {
        when(skillRepository.findAllWithCapabilities()).thenReturn(List.of());

        StringBuilder sb = new StringBuilder();
        section.append(sb, true);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void full_showsSkillDetailsWithCapabilityCount() {
        when(skillRepository.findAllWithCapabilities()).thenReturn(List.of(
                skill("s1", "Web Research", SkillCategory.RESEARCH, "Recherchiert URLs", 3)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("**Skills (1):**")
                .contains("[RESEARCH] Web Research")
                .contains("Recherchiert URLs")
                .contains("3 Capabilities, id: s1");
    }

    @Test
    void full_truncatesAtMaxEntriesWithHint() {
        List<Skill> many = IntStream.range(0, 25)
                .mapToObj(i -> skill("id-" + i, "Skill " + i, SkillCategory.PRODUCTIVITY, "d", 1))
                .toList();
        when(skillRepository.findAllWithCapabilities()).thenReturn(many);

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString())
                .contains("**Skills (25):**")
                .contains("… und 5 weitere")
                .contains("<fetch type=\"skill\"");
    }

    @Test
    void full_handlesNullDescription() {
        when(skillRepository.findAllWithCapabilities()).thenReturn(List.of(
                skill("s1", "NoDesc", SkillCategory.CODE, null, 0)
        ));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).contains("(keine Beschreibung)");
    }

    @Test
    void exception_doesNotThrow() {
        when(skillRepository.findAllWithCapabilities()).thenThrow(new RuntimeException("boom"));

        StringBuilder sb = new StringBuilder();
        section.append(sb, false);

        assertThat(sb.toString()).isEmpty();
    }
}
