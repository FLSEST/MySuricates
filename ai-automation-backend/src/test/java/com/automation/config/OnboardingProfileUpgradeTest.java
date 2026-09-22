package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.project.ProjectOnboardingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Shipping a newer text (TODOs a805d0a7, 644fd5ad): an unchanged profile takes it over, an edited
 * one keeps its text and shows "update available" — never skipped silently, never overwritten.
 */
class OnboardingProfileUpgradeTest {

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final OnboardingProfileHistoryRepository historyRepository = mock(OnboardingProfileHistoryRepository.class);
    private final ProjectOnboardingService projectOnboardingService = mock(ProjectOnboardingService.class);
    private OnboardingProfileSeeder seeder;
    private int shipped;

    @BeforeEach
    void setUp() {
        seeder = new OnboardingProfileSeeder(profileRepository, historyRepository, projectOnboardingService);
        shipped = seeder.shippedVersion("developer");
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.of(new OnboardingProfile()));
    }

    private OnboardingProfile stored(int shippedVersion, boolean customized, Map<String, String> content) {
        OnboardingProfile profile = OnboardingProfile.builder().id("onboarding-developer").key("developer")
                .type(OnboardingProfileType.PROFILE).builtIn(true).version(7)
                .shippedVersion(shippedVersion).customized(customized).content(new HashMap<>(content)).build();
        when(profileRepository.findById("onboarding-developer")).thenReturn(Optional.of(profile));
        return profile;
    }

    private void latestChangeBy(String who) {
        OnboardingProfileHistory latest = OnboardingProfileHistory.builder().profileId("onboarding-developer")
                .version(6).content(Map.of("de", "before")).changedBy(who).changedAt(Instant.now()).build();
        when(historyRepository.findByProfileIdOrderByVersionDesc("onboarding-developer")).thenReturn(List.of(latest));
    }

    @Test
    void anUnchangedProfileTakesOverTheNewerText_withTheOldOneInTheHistory() {
        OnboardingProfile profile = stored(shipped - 1, false, Map.of("de", "alte mitgelieferte Fassung"));

        seeder.run(null);

        assertThat(profile.getContent()).isEqualTo(seeder.getDefaultContent("developer"));
        assertThat(profile.getShippedVersion()).isEqualTo(shipped);
        assertThat(profile.getVersion()).isEqualTo(8);
        assertThat(seeder.isUpdateAvailable(profile)).isFalse();
        ArgumentCaptor<OnboardingProfileHistory> history = ArgumentCaptor.forClass(OnboardingProfileHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getContent()).containsEntry("de", "alte mitgelieferte Fassung");
        assertThat(history.getValue().getChangedBy()).isEqualTo("system");
        verify(projectOnboardingService).refreshEnabled(any());
    }

    @Test
    void anEditedProfileKeepsItsText_andAnUpdateIsOffered() {
        OnboardingProfile profile = stored(shipped - 1, true, Map.of("de", "von Hand"));

        seeder.run(null);

        assertThat(profile.getContent()).containsOnlyKeys("de").containsEntry("de", "von Hand");
        assertThat(seeder.isUpdateAvailable(profile)).isTrue();
        verify(historyRepository, never()).save(any());
    }

    @Test
    void beforeV83_aProfileEqualToTheShippedTextIsRecordedAsCurrent() {
        OnboardingProfile profile = stored(0, false, seeder.getDefaultContent("developer"));

        seeder.run(null);

        assertThat(profile.getShippedVersion()).isEqualTo(shipped);
        assertThat(profile.isCustomized()).isFalse();
        verify(historyRepository, never()).save(any());
    }

    @Test
    void beforeV83_aProfileLastChangedByHandCountsAsEdited() {
        OnboardingProfile profile = stored(0, false, Map.of("de", "von Hand geändert"));
        latestChangeBy("user");

        seeder.run(null);

        assertThat(profile.isCustomized()).isTrue();
        assertThat(profile.getContent()).containsEntry("de", "von Hand geändert");
        assertThat(seeder.isUpdateAvailable(profile)).isTrue();
    }

    @Test
    void beforeV83_anOlderShippedTextNobodyEditedIsReplaced() {
        OnboardingProfile profile = stored(0, false, Map.of("de", "Fassung aus V81"));
        latestChangeBy("system");

        seeder.run(null);

        assertThat(profile.isCustomized()).isFalse();
        assertThat(profile.getContent()).isEqualTo(seeder.getDefaultContent("developer"));
        assertThat(profile.getShippedVersion()).isEqualTo(shipped);
    }
}
