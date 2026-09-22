package com.automation.service.project;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingContentMergeTest {

    @Test
    void sentLanguagesReplace_blankOnesRemove_othersStay() {
        Map<String, String> changes = new HashMap<>();
        changes.put("en", "new English");
        changes.put("de", "  ");

        Map<String, String> merged = OnboardingProfileService.mergeContent(
                Map.of("de", "Deutsch", "en", "old English"), changes);

        assertThat(merged).containsOnlyKeys("en").containsEntry("en", "new English");
    }

    @Test
    void languagesNotSentAreKept() {
        assertThat(OnboardingProfileService.mergeContent(Map.of("de", "Deutsch"), Map.of("en", "English")))
                .containsEntry("de", "Deutsch").containsEntry("en", "English");
    }

    @Test
    void nullsAndBlanksNeverSurvive() {
        Map<String, String> current = new HashMap<>();
        current.put("de", null);
        current.put("en", "");

        assertThat(OnboardingProfileService.mergeContent(current, null)).isEmpty();
    }
}
