package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OnboardingProfileSeeder implements ApplicationRunner {

    private final OnboardingProfileRepository profileRepository;

    private static final List<ProfileDefinition> BUILT_IN_PROFILES = List.of(
            new ProfileDefinition("onboarding-base", "base",
                    Map.of("de", "Basis", "en", "Base"),
                    Map.of("de", "MySuricates API-Referenz und Grundregeln", "en", "MySuricates API reference and basic rules"),
                    "onboarding/base.md", OnboardingProfileType.BASE, 0),
            new ProfileDefinition("onboarding-developer", "developer",
                    Map.of("de", "Entwickler", "en", "Developer"),
                    Map.of("de", "Code-Review, Test-Pipelines, Auto-Fix, Quality Gates", "en", "Code review, test pipelines, auto-fix, quality gates"),
                    "onboarding/profiles/developer.md", OnboardingProfileType.PROFILE, 1),
            new ProfileDefinition("onboarding-research", "research",
                    Map.of("de", "Forschung", "en", "Research"),
                    Map.of("de", "Analyse-Workflows, Zusammenfassungen, Daten-Pipelines", "en", "Analysis workflows, summaries, data pipelines"),
                    "onboarding/profiles/research.md", OnboardingProfileType.PROFILE, 2),
            new ProfileDefinition("onboarding-content", "content",
                    Map.of("de", "Content", "en", "Content"),
                    Map.of("de", "Content-Generierung, Review, Batch-Verarbeitung", "en", "Content generation, review, batch processing"),
                    "onboarding/profiles/content.md", OnboardingProfileType.PROFILE, 3),
            new ProfileDefinition("onboarding-ops", "ops",
                    Map.of("de", "Operations", "en", "Operations"),
                    Map.of("de", "Monitoring, Log-Analyse, Deployment-Chains", "en", "Monitoring, log analysis, deployment chains"),
                    "onboarding/profiles/ops.md", OnboardingProfileType.PROFILE, 4)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int seeded = 0;

        for (ProfileDefinition def : BUILT_IN_PROFILES) {
            if (profileRepository.findById(def.id()).isPresent()) {
                continue;
            }
            if (profileRepository.findByKey(def.key()).isPresent()) {
                continue;
            }

            String content = loadResource(def.resourcePath());
            if (content == null) {
                log.warn("Could not load onboarding profile resource: {}", def.resourcePath());
                continue;
            }

            OnboardingProfile profile = OnboardingProfile.builder()
                    .id(def.id())
                    .key(def.key())
                    .name(def.name())
                    .description(def.description())
                    .content(content)
                    .type(def.type())
                    .builtIn(true)
                    .sortOrder(def.sortOrder())
                    .version(1)
                    .build();

            profileRepository.save(profile);
            seeded++;
        }

        if (seeded > 0) {
            log.info("Seeded {} onboarding profiles", seeded);
        }
    }

    public String getDefaultContent(String key) {
        return BUILT_IN_PROFILES.stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .map(d -> loadResource(d.resourcePath()))
                .orElse(null);
    }

    private String loadResource(String path) {
        try {
            ClassPathResource resource = new ClassPathResource(path);
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private record ProfileDefinition(
            String id, String key,
            Map<String, String> name, Map<String, String> description,
            String resourcePath, OnboardingProfileType type, int sortOrder
    ) {}
}
