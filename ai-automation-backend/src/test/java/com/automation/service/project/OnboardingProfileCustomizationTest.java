package com.automation.service.project;

import com.automation.config.OnboardingProfileSeeder;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Editing marks a built-in profile as customized, resetting clears it; both rewrite the project files (TODO 644fd5ad). */
class OnboardingProfileCustomizationTest {

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final OnboardingProfileSeeder seeder = mock(OnboardingProfileSeeder.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final OnboardingProfileService service = new OnboardingProfileService(
            profileRepository, mock(OnboardingProfileHistoryRepository.class), seeder, events);
    private OnboardingProfile profile;

    @BeforeEach
    void setUp() {
        profile = OnboardingProfile.builder().id("dev-id").key("developer").type(OnboardingProfileType.PROFILE)
                .builtIn(true).version(3).shippedVersion(3).content(new HashMap<>(Map.of("de", "mitgeliefert"))).build();
        when(profileRepository.findById("dev-id")).thenReturn(Optional.of(profile));
        when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void editingTheTextMarksTheProfileCustomized_andRewritesTheFiles() {
        service.update("dev-id", new UpdateOnboardingProfileRequest(null, null, Map.of("de", "von Hand")));

        assertThat(profile.isCustomized()).isTrue();
        verify(events).publishEvent(new OnboardingProfileChangedEvent("dev-id", false));
    }

    @Test
    void resettingTakesOverTheCurrentShippedText_andClearsTheMark() {
        profile.setCustomized(true);
        when(seeder.getDefaultContent("developer")).thenReturn(Map.of("de", "neu", "en", "new"));
        when(seeder.shippedVersion("developer")).thenReturn(4);

        service.resetToDefault("dev-id");

        assertThat(profile.getContent()).containsEntry("en", "new");
        assertThat(profile.isCustomized()).isFalse();
        assertThat(profile.getShippedVersion()).isEqualTo(4);
        verify(events).publishEvent(new OnboardingProfileChangedEvent("dev-id", false));
    }
}
