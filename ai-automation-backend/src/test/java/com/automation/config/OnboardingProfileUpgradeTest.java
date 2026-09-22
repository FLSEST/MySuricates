package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

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

/** Shipping the bilingual texts (TODO a805d0a7) must not wipe out what was stored before without a trace. */
class OnboardingProfileUpgradeTest {

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final OnboardingProfileHistoryRepository historyRepository = mock(OnboardingProfileHistoryRepository.class);
    private final ProjectService projectService = mock(ProjectService.class);
    private OnboardingProfileSeeder seeder;

    @BeforeEach
    void setUp() {
        seeder = new OnboardingProfileSeeder(profileRepository, historyRepository, projectService,
                mock(ProjectOnboardingService.class));
        when(profileRepository.findById(anyString())).thenReturn(Optional.empty());
        when(profileRepository.findByKey(anyString())).thenReturn(Optional.of(new OnboardingProfile()));
        when(projectService.findAll()).thenReturn(List.of());
    }

    private OnboardingProfile storedBase(int version, String content) {
        OnboardingProfile base = OnboardingProfile.builder().id("onboarding-base").key("base")
                .type(OnboardingProfileType.BASE).builtIn(true).version(version)
                .content(new HashMap<>(Map.of("de", content))).build();
        when(profileRepository.findById("onboarding-base")).thenReturn(Optional.of(base));
        return base;
    }

    @Test
    void anOlderVersionGetsBothLanguages_andTheOldTextGoesIntoTheHistory() {
        OnboardingProfile base = storedBase(3, "alter deutscher Text");

        seeder.run(null);

        assertThat(base.getVersion()).isEqualTo(4);
        assertThat(base.getContent()).containsOnlyKeys("de", "en");
        assertThat(base.getContent().get("en")).contains("{{BASE_URL}}");
        ArgumentCaptor<OnboardingProfileHistory> history = ArgumentCaptor.forClass(OnboardingProfileHistory.class);
        verify(historyRepository).save(history.capture());
        assertThat(history.getValue().getContent()).containsEntry("de", "alter deutscher Text");
        assertThat(history.getValue().getVersion()).isEqualTo(3);
        assertThat(history.getValue().getChangedBy()).isEqualTo("system");
    }

    @Test
    void aProfileAtTheShippedVersionOrAboveIsLeftAlone() {
        OnboardingProfile base = storedBase(5, "von Hand bearbeitet");

        seeder.run(null);

        assertThat(base.getContent()).containsOnlyKeys("de").containsEntry("de", "von Hand bearbeitet");
        verify(historyRepository, never()).save(any());
    }
}
