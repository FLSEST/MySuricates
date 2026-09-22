package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingProfileSeederTest {

    @Mock private OnboardingProfileRepository profileRepository;
    @Mock private OnboardingProfileHistoryRepository historyRepository;
    @Mock private ProjectService projectService;
    @Mock private ProjectOnboardingService projectOnboardingService;

    private OnboardingProfileSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new OnboardingProfileSeeder(profileRepository, historyRepository, projectService, projectOnboardingService);
    }

    @Test
    void run_seedsProfilesWhenNoneExist() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        // 6 built-in profiles should be seeded
        verify(profileRepository, times(6)).save(any());
    }

    @Test
    void run_skipsExistingProfileById_whenVersionCurrent() throws Exception {
        OnboardingProfile existing = OnboardingProfile.builder()
                .id("onboarding-base").key("base").version(99).build();
        when(profileRepository.findById("onboarding-base")).thenReturn(Optional.of(existing));
        when(profileRepository.findById(argThat(id -> !"onboarding-base".equals(id)))).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        // 5 profiles seeded (base skipped because found by ID at current version)
        verify(profileRepository, times(5)).save(any());
    }

    @Test
    void run_skipsExistingProfileByKey() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey("developer")).thenReturn(Optional.of(
                OnboardingProfile.builder().key("developer").build()));
        when(profileRepository.findByKey(argThat(k -> !"developer".equals(k)))).thenReturn(Optional.empty());

        seeder.run(null);

        // 5 profiles seeded (developer skipped because found by key)
        verify(profileRepository, times(5)).save(any());
    }

    @Test
    void run_allExist_atCurrentVersion_seedsNothing() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.of(
                OnboardingProfile.builder().version(99).build()));

        seeder.run(null);

        verify(profileRepository, never()).save(any());
    }

    @Test
    void run_idempotent_secondCallSeedsNothing() throws Exception {
        // First call: nothing exists
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());
        seeder.run(null);
        verify(profileRepository, times(6)).save(any());

        // Second call: all exist at current version
        reset(profileRepository);
        when(profileRepository.findById(anyString())).thenReturn(Optional.of(
                OnboardingProfile.builder().version(99).build()));
        seeder.run(null);
        verify(profileRepository, never()).save(any());
    }

    @Test
    void run_seededProfilesAreBuiltIn() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<OnboardingProfile> captor = ArgumentCaptor.forClass(OnboardingProfile.class);
        verify(profileRepository, times(6)).save(captor.capture());
        captor.getAllValues().forEach(profile -> {
            assertThat(profile.isBuiltIn()).isTrue();
            assertThat(profile.getVersion()).isGreaterThanOrEqualTo(1);
            assertThat(profile.getContent()).containsOnlyKeys("de", "en");
            assertThat(profile.getContent().values()).allSatisfy(text -> assertThat(text).isNotBlank());
        });
    }

    @Test
    void run_baseProfileHasCorrectType() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<OnboardingProfile> captor = ArgumentCaptor.forClass(OnboardingProfile.class);
        verify(profileRepository, times(6)).save(captor.capture());

        OnboardingProfile base = captor.getAllValues().stream()
                .filter(p -> "base".equals(p.getKey()))
                .findFirst().orElseThrow();
        assertThat(base.getType()).isEqualTo(OnboardingProfileType.BASE);
        assertThat(base.getSortOrder()).isEqualTo(0);
        assertThat(base.getName()).containsEntry("de", "Basis");
        assertThat(base.getName()).containsEntry("en", "Base");
    }

    @Test
    void run_profilesHaveCorrectSortOrder() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<OnboardingProfile> captor = ArgumentCaptor.forClass(OnboardingProfile.class);
        verify(profileRepository, times(6)).save(captor.capture());

        var profiles = captor.getAllValues();
        int[] expectedOrder = {0, 1, 2, 3, 4, 5};
        for (int i = 0; i < profiles.size(); i++) {
            assertThat(profiles.get(i).getSortOrder()).isEqualTo(expectedOrder[i]);
        }
    }

    @Test
    void run_updatesProfileWhenVersionIsLower() throws Exception {
        OnboardingProfile outdated = OnboardingProfile.builder()
                .id("onboarding-base").key("base").version(1).content(Map.of("de", "old content")).build();
        when(profileRepository.findById("onboarding-base")).thenReturn(Optional.of(outdated));
        when(profileRepository.findById(argThat(id -> !"onboarding-base".equals(id)))).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());
        when(projectService.findAll()).thenReturn(List.of());

        seeder.run(null);

        // base updated (version 1 < 4) + 5 others seeded = 6 saves
        verify(profileRepository, times(6)).save(any());
        assertThat(outdated.getVersion()).isEqualTo(4);
        assertThat(outdated.getContent()).isNotEqualTo(Map.of("de", "old content"));
        assertThat(outdated.getContent().get("de")).contains("TODOs immer mit Titel nennen");
    }

    @Test
    void run_refreshesEnabledProjectsAfterUpdate() throws Exception {
        OnboardingProfile outdated = OnboardingProfile.builder()
                .id("onboarding-base").key("base").version(1).content(Map.of("de", "old")).build();
        when(profileRepository.findById("onboarding-base")).thenReturn(Optional.of(outdated));
        when(profileRepository.findById(argThat(id -> !"onboarding-base".equals(id)))).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        var project = mock(com.automation.entity.Project.class);
        when(project.getConfig()).thenReturn(Map.of("onboarding", Map.of("enabled", true)));
        when(projectService.findAll()).thenReturn(List.of(project));

        seeder.run(null);

        verify(projectOnboardingService).refresh(project);
    }

    @Test
    void getDefaultContent_existingKey_returnsContent() {
        Map<String, String> content = seeder.getDefaultContent("base");
        assertThat(content).containsOnlyKeys("de", "en");
        assertThat(content.values()).allSatisfy(text -> assertThat(text).isNotBlank());
    }

    @Test
    void getDefaultContent_unknownKey_returnsNull() {
        Map<String, String> content = seeder.getDefaultContent("nonexistent");
        assertThat(content).isNull();
    }
}
