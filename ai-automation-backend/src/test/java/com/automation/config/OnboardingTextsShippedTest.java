package com.automation.config;

import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Gate over the shipped onboarding texts (TODO a805d0a7) — the files agents in foreign projects
 * read. The old German-only texts carried a hard-coded port, a placeholder nobody filled and cron
 * examples the API rejects; nothing here would have caught that.
 */
class OnboardingTextsShippedTest {

    private static final List<String> KEYS = List.of("base", "developer", "research", "content", "ops", "platform");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Z_]+)}}");
    private static final List<String> FILLED_BY_RENDERER = List.of("PROJECT_ID", "BASE_URL", "API_KEY_COMMAND");
    private static final Pattern CRON_FIELD = Pattern.compile("\"cronExpression\":\\s*\"([^\"]+)\"");

    private final OnboardingProfileSeeder seeder = new OnboardingProfileSeeder(
            mock(OnboardingProfileRepository.class), mock(OnboardingProfileHistoryRepository.class),
            mock(ProjectService.class), mock(ProjectOnboardingService.class));

    @Test
    void everyBuiltInProfileIsShippedInGermanAndEnglish() {
        for (String key : KEYS) {
            Map<String, String> content = seeder.getDefaultContent(key);
            assertThat(content).as(key).containsOnlyKeys("de", "en");
            content.forEach((lang, text) -> assertThat(text).as(key + "/" + lang).isNotBlank());
        }
    }

    @Test
    void bothLanguagesHaveTheSameStructure() {
        for (String key : KEYS) {
            Map<String, String> content = seeder.getDefaultContent(key);
            assertThat(headingLevels(content.get("en"))).as(key + " headings").isEqualTo(headingLevels(content.get("de")));
            assertThat(placeholders(content.get("en"))).as(key + " placeholders").isEqualTo(placeholders(content.get("de")));
        }
    }

    @Test
    void onlyPlaceholdersTheRendererFillsAreUsed() {
        for (String key : KEYS) {
            seeder.getDefaultContent(key).forEach((lang, text) ->
                    assertThat(placeholders(text)).as(key + "/" + lang).isSubsetOf(FILLED_BY_RENDERER));
        }
    }

    @Test
    void noFixedAddressOrPort_theInstanceFillsItIn() {
        for (String key : KEYS) {
            seeder.getDefaultContent(key).forEach((lang, text) -> {
                assertThat(text).as(key + "/" + lang).doesNotContain("localhost:").doesNotContain("~/.autotrigger");
                assertThat(text).as(key + "/" + lang).doesNotContain("{\"inputContext\"");
            });
        }
    }

    @Test
    void cronExamplesHaveTheSixFieldsSpringExpects() {
        for (String key : KEYS) {
            seeder.getDefaultContent(key).forEach((lang, text) -> {
                Matcher cron = CRON_FIELD.matcher(text);
                while (cron.find()) {
                    assertThat(cron.group(1).trim().split("\\s+")).as(key + "/" + lang + ": " + cron.group(1)).hasSize(6);
                }
            });
        }
    }

    private static List<Integer> headingLevels(String text) {
        List<Integer> levels = new ArrayList<>();
        boolean inCode = false;
        for (String line : text.split("\n")) {
            if (line.startsWith("```")) {
                inCode = !inCode;
            } else if (!inCode && line.startsWith("#")) {
                levels.add(line.indexOf(' '));
            }
        }
        return levels;
    }

    private static List<String> placeholders(String text) {
        List<String> found = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }
}
