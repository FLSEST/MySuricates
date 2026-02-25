package com.automation.config;

import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SkillSeederTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillRegistry skillRegistry;

    private SkillSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new SkillSeeder(skillRepository, skillRegistry);
    }

    @Test
    void run_emptyDb_seedsAllSkills() throws Exception {
        when(skillRepository.findByBuiltin(true)).thenReturn(List.of());
        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        int expectedCount = BuiltInSkills.getAll().size();
        verify(skillRepository, times(expectedCount)).save(any(Skill.class));
        verify(skillRegistry, times(expectedCount)).register(any(Skill.class));
    }

    @Test
    void run_allExist_skipsSeeding() throws Exception {
        List<Skill> existingSkills = new ArrayList<>();
        for (int i = 0; i < BuiltInSkills.getAll().size(); i++) {
            existingSkills.add(Skill.builder()
                    .id("existing-" + i)
                    .name("Existing " + i)
                    .category(SkillCategory.CODE)
                    .builtin(true)
                    .build());
        }
        when(skillRepository.findByBuiltin(true)).thenReturn(existingSkills);

        seeder.run(null);

        verify(skillRepository, never()).save(any(Skill.class));
        // But all existing skills should be registered in the registry
        verify(skillRegistry, times(existingSkills.size())).register(any(Skill.class));
    }

    @Test
    void run_partialState_seedsOnlyMissing() throws Exception {
        // Simulate: first skill already exists, rest are missing
        Skill existingSkill = BuiltInSkills.getAll().get(0);
        when(skillRepository.findByBuiltin(true)).thenReturn(List.of(existingSkill));
        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        int expectedNewSkills = BuiltInSkills.getAll().size() - 1;
        verify(skillRepository, times(expectedNewSkills)).save(any(Skill.class));

        // All skills (existing + new) should be registered
        int totalRegistered = BuiltInSkills.getAll().size();
        verify(skillRegistry, times(totalRegistered)).register(any(Skill.class));
    }

    @Test
    void run_registersSkillsInRegistry() throws Exception {
        when(skillRepository.findByBuiltin(true)).thenReturn(List.of());
        when(skillRepository.save(any(Skill.class))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillRegistry, atLeastOnce()).register(captor.capture());

        List<Skill> registered = captor.getAllValues();
        assertThat(registered).isNotEmpty();
        assertThat(registered.get(0).getId()).isNotBlank();
    }
}
