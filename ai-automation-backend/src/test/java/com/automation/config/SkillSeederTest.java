package com.automation.config;

import com.automation.entity.Capability;
import com.automation.entity.Skill;
import com.automation.entity.SkillCategory;
import com.automation.entity.SkillChangeSource;
import com.automation.repository.SkillRepository;
import com.automation.service.skill.SkillMutationGateway;
import com.automation.service.skill.SkillRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SkillSeederTest {

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private SkillRegistry skillRegistry;

    @Mock
    private SkillMutationGateway gateway;

    private BuiltInSkillLoader loader;
    private SkillSeeder seeder;

    @BeforeEach
    void setUp() {
        loader = new BuiltInSkillLoader(new ObjectMapper());
        seeder = new SkillSeeder(skillRepository, skillRegistry, gateway, loader);
    }

    private Skill firstBuiltIn() {
        return loader.loadAll().get(0);
    }

    /** Kopie eines Built-ins, wie sie in der DB laege — mit frei waehlbarer Version. */
    private Skill dbCopy(Skill source, String version) {
        Skill copy = Skill.builder()
                .id(source.getId())
                .name("Alter Name")
                .icon("❔")
                .description("Alte Beschreibung")
                .category(SkillCategory.DATA)
                .aiContext("Alter Prompt")
                .builtin(true)
                .version(version)
                .build();
        copy.setCapabilities(new ArrayList<>());
        return copy;
    }

    @Test
    void run_emptyDb_seedsAllSkills() {
        when(skillRepository.findByBuiltin(true)).thenReturn(List.of());
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        int expected = loader.loadAll().size();
        verify(gateway, times(expected)).save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED));
        verify(skillRegistry, times(expected)).register(any(Skill.class));
    }

    @Test
    void run_sameVersion_doesNotUpdate() {
        List<Skill> existing = loader.loadAll().stream()
                .map(s -> dbCopy(s, "1.0.0"))
                .toList();
        when(skillRepository.findByBuiltin(true)).thenReturn(existing);

        seeder.run(null);

        verify(gateway, never()).save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED));
        verify(skillRegistry, times(existing.size())).register(any(Skill.class));
    }

    @Test
    void run_olderVersionInDb_updatesFromResources() {
        Skill fresh = firstBuiltIn();
        Skill stale = dbCopy(fresh, "0.9.0");
        when(skillRepository.findByBuiltin(true)).thenReturn(new ArrayList<>(List.of(stale)));
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(gateway, times(loader.loadAll().size())).save(captor.capture(), eq(SkillChangeSource.BUILTIN_SEED));

        Skill updated = captor.getAllValues().stream()
                .filter(s -> s.getId().equals(fresh.getId()))
                .findFirst().orElseThrow();

        assertThat(updated).isSameAs(stale);
        assertThat(updated.getName()).isEqualTo(fresh.getName());
        assertThat(updated.getIcon()).isEqualTo(fresh.getIcon());
        assertThat(updated.getDescription()).isEqualTo(fresh.getDescription());
        assertThat(updated.getCategory()).isEqualTo(fresh.getCategory());
        assertThat(updated.getAiContext()).isEqualTo(fresh.getAiContext());
        assertThat(updated.getVersion()).isEqualTo("1.0.0");
        assertThat(updated.getCapabilities())
                .extracting(Capability::getName)
                .containsExactlyElementsOf(fresh.getCapabilities().stream().map(Capability::getName).toList());
        assertThat(updated.getCapabilities()).allSatisfy(c -> assertThat(c.getSkill()).isSameAs(stale));
    }

    @Test
    void run_newerVersionInDb_isNotDowngraded() {
        Skill fresh = firstBuiltIn();
        Skill newer = dbCopy(fresh, "2.0.0");
        when(skillRepository.findByBuiltin(true)).thenReturn(new ArrayList<>(List.of(newer)));
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(gateway, times(loader.loadAll().size() - 1)).save(captor.capture(), eq(SkillChangeSource.BUILTIN_SEED));
        assertThat(captor.getAllValues()).extracting(Skill::getId).doesNotContain(fresh.getId());
        assertThat(newer.getName()).isEqualTo("Alter Name");
        assertThat(newer.getVersion()).isEqualTo("2.0.0");
    }

    @Test
    void run_partialState_seedsOnlyMissing() {
        Skill existing = dbCopy(firstBuiltIn(), "1.0.0");
        when(skillRepository.findByBuiltin(true)).thenReturn(new ArrayList<>(List.of(existing)));
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        verify(gateway, times(loader.loadAll().size() - 1)).save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED));
        verify(skillRegistry, times(loader.loadAll().size())).register(any(Skill.class));
    }

    @Test
    void run_neverTouchesUserSkills() {
        // Nutzer-Skills sind builtin=false und tauchen in findByBuiltin(true) nicht auf.
        when(skillRepository.findByBuiltin(true)).thenReturn(List.of());
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(gateway, times(loader.loadAll().size())).save(captor.capture(), eq(SkillChangeSource.BUILTIN_SEED));
        assertThat(captor.getAllValues()).allSatisfy(s -> {
            assertThat(s.isBuiltin()).isTrue();
            assertThat(s.getId()).startsWith("builtin-");
        });
        verify(gateway, never()).delete(any(Skill.class), any());
        verify(skillRepository, never()).deleteAll();
    }

    @Test
    void run_registersUnknownBuiltInsFromDbToo() {
        // Ein Built-in, das es in den Ressourcen nicht mehr gibt, bleibt erhalten und registriert.
        Skill orphan = Skill.builder()
                .id("builtin-retired")
                .name("Retired")
                .category(SkillCategory.CODE)
                .builtin(true)
                .version("1.0.0")
                .build();
        when(skillRepository.findByBuiltin(true)).thenReturn(new ArrayList<>(List.of(orphan)));
        when(gateway.save(any(Skill.class), eq(SkillChangeSource.BUILTIN_SEED))).thenAnswer(inv -> inv.getArgument(0));

        seeder.run(null);

        ArgumentCaptor<Skill> captor = ArgumentCaptor.forClass(Skill.class);
        verify(skillRegistry, times(loader.loadAll().size() + 1)).register(captor.capture());
        assertThat(captor.getAllValues()).extracting(Skill::getId).contains("builtin-retired");
        verify(gateway, never()).delete(any(Skill.class), any());
    }
}
