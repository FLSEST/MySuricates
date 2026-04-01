package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingProfileSeederTest {

    @Mock private OnboardingProfileRepository profileRepository;

    private OnboardingProfileSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new OnboardingProfileSeeder(profileRepository);
    }

    @Test
    void run_seedsProfilesWhenNoneExist() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        // 5 built-in profiles should be seeded
        verify(profileRepository, times(5)).save(any());
    }

    @Test
    void run_skipsExistingProfileById() throws Exception {
        OnboardingProfile existing = OnboardingProfile.builder()
                .id("onboarding-base").key("base").build();
        when(profileRepository.findById("onboarding-base")).thenReturn(Optional.of(existing));
        when(profileRepository.findById(argThat(id -> !"onboarding-base".equals(id)))).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        // 4 profiles seeded (base skipped because found by ID)
        verify(profileRepository, times(4)).save(any());
    }

    @Test
    void run_skipsExistingProfileByKey() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey("developer")).thenReturn(Optional.of(
                OnboardingProfile.builder().key("developer").build()));
        when(profileRepository.findByKey(argThat(k -> !"developer".equals(k)))).thenReturn(Optional.empty());

        seeder.run(null);

        // 4 profiles seeded (developer skipped because found by key)
        verify(profileRepository, times(4)).save(any());
    }

    @Test
    void run_allExist_seedsNothing() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.of(
                OnboardingProfile.builder().build()));

        seeder.run(null);

        verify(profileRepository, never()).save(any());
    }

    @Test
    void run_idempotent_secondCallSeedsNothing() throws Exception {
        // First call: nothing exists
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());
        seeder.run(null);
        verify(profileRepository, times(5)).save(any());

        // Second call: all exist
        reset(profileRepository);
        when(profileRepository.findById(anyString())).thenReturn(Optional.of(
                OnboardingProfile.builder().build()));
        seeder.run(null);
        verify(profileRepository, never()).save(any());
    }

    @Test
    void run_seededProfilesAreBuiltIn() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<OnboardingProfile> captor = ArgumentCaptor.forClass(OnboardingProfile.class);
        verify(profileRepository, times(5)).save(captor.capture());
        captor.getAllValues().forEach(profile -> {
            assertThat(profile.isBuiltIn()).isTrue();
            assertThat(profile.getVersion()).isEqualTo(1);
            assertThat(profile.getContent()).isNotNull().isNotBlank();
        });
    }

    @Test
    void run_baseProfileHasCorrectType() throws Exception {
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.empty());

        seeder.run(null);

        ArgumentCaptor<OnboardingProfile> captor = ArgumentCaptor.forClass(OnboardingProfile.class);
        verify(profileRepository, times(5)).save(captor.capture());

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
        verify(profileRepository, times(5)).save(captor.capture());

        var profiles = captor.getAllValues();
        int[] expectedOrder = {0, 1, 2, 3, 4};
        for (int i = 0; i < profiles.size(); i++) {
            assertThat(profiles.get(i).getSortOrder()).isEqualTo(expectedOrder[i]);
        }
    }

    @Test
    void getDefaultContent_existingKey_returnsContent() {
        String content = seeder.getDefaultContent("base");
        assertThat(content).isNotNull().isNotBlank();
    }

    @Test
    void getDefaultContent_unknownKey_returnsNull() {
        String content = seeder.getDefaultContent("nonexistent");
        assertThat(content).isNull();
    }
}
