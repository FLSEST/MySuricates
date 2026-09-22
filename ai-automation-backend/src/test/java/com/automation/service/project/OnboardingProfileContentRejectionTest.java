package com.automation.service.project;

import com.automation.config.OnboardingProfileSeeder;
import com.automation.dto.request.CreateOnboardingProfileRequest;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Bean validation only rejects an empty map; a profile whose every language is blank is refused here. */
class OnboardingProfileContentRejectionTest {

    private final OnboardingProfileRepository profileRepository = mock(OnboardingProfileRepository.class);
    private final OnboardingProfileService service = new OnboardingProfileService(
            profileRepository, mock(OnboardingProfileHistoryRepository.class), mock(OnboardingProfileSeeder.class));

    @Test
    void creatingWithOnlyBlankLanguagesIsRefused() {
        when(profileRepository.findByKey("mine")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.create(new CreateOnboardingProfileRequest(
                "mine", Map.of("en", "Mine"), Map.of(), Map.of("de", "  ", "en", ""), OnboardingProfileType.PROFILE)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least one language");
        verify(profileRepository, never()).save(any());
    }

    @Test
    void blankingTheLastLanguageIsRefused() {
        OnboardingProfile profile = OnboardingProfile.builder().id("p1").key("mine")
                .type(OnboardingProfileType.PROFILE).content(new HashMap<>(Map.of("de", "Text"))).build();
        when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

        assertThatThrownBy(() -> service.update("p1", new UpdateOnboardingProfileRequest(null, null, Map.of("de", " "))))
                .isInstanceOf(IllegalArgumentException.class);
        verify(profileRepository, never()).save(any());
    }
}
