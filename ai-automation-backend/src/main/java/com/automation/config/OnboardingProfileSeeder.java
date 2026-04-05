package com.automation.config;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import com.automation.entity.Project;
import com.automation.repository.OnboardingProfileRepository;
import com.automation.service.project.ProjectOnboardingService;
import com.automation.service.project.ProjectService;
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
    private final ProjectService projectService;
    private final ProjectOnboardingService projectOnboardingService;

    private static final List<ProfileDefinition> BUILT_IN_PROFILES = List.of(
            new ProfileDefinition("onboarding-base", "base",
                    Map.of("de", "Basis", "en", "Base"),
                    Map.of("de", "MySuricates API-Referenz und Grundregeln", "en", "MySuricates API reference and basic rules"),
                    "onboarding/base.md", OnboardingProfileType.BASE, 0, 2),
            new ProfileDefinition("onboarding-developer", "developer",
                    Map.of("de", "Entwickler", "en", "Developer"),
                    Map.of("de", "Code-Review, Test-Pipelines, Auto-Fix, Quality Gates, vollständige API-Referenz", "en", "Code review, test pipelines, auto-fix, quality gates, full API reference"),
                    "onboarding/profiles/developer.md", OnboardingProfileType.PROFILE, 1, 2),
            new ProfileDefinition("onboarding-research", "research",
                    Map.of("de", "Forschung", "en", "Research"),
                    Map.of("de", "Analyse-Workflows, Zusammenfassungen, Daten-Pipelines, Run-History, Workflow Memory", "en", "Analysis workflows, summaries, data pipelines, run history, workflow memory"),
                    "onboarding/profiles/research.md", OnboardingProfileType.PROFILE, 2, 2),
            new ProfileDefinition("onboarding-content", "content",
                    Map.of("de", "Content", "en", "Content"),
                    Map.of("de", "Content-Generierung, Review, Batch-Verarbeitung, Run-History", "en", "Content generation, review, batch processing, run history"),
                    "onboarding/profiles/content.md", OnboardingProfileType.PROFILE, 3, 2),
            new ProfileDefinition("onboarding-ops", "ops",
                    Map.of("de", "Operations", "en", "Operations"),
                    Map.of("de", "Monitoring, Log-Analyse, Deployment-Chains, Schedules, Webhooks, Activity Log", "en", "Monitoring, log analysis, deployment chains, schedules, webhooks, activity log"),
                    "onboarding/profiles/ops.md", OnboardingProfileType.PROFILE, 4, 2),
            new ProfileDefinition("onboarding-platform", "platform",
                    Map.of("de", "Plattform", "en", "Platform"),
                    Map.of("de", "Gate-Status, AutoTodo, TodoTrigger, Modelle, Variablen, Agentic-Safety", "en", "Gate status, auto-todo, todo triggers, models, variables, agentic safety"),
                    "onboarding/profiles/platform.md", OnboardingProfileType.PROFILE, 5, 1)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int seeded = 0;
        int updated = 0;

        for (ProfileDefinition def : BUILT_IN_PROFILES) {
            var existing = profileRepository.findById(def.id());
            if (existing.isPresent()) {
                OnboardingProfile profile = existing.get();
                if (profile.getVersion() < def.version()) {
                    String content = loadResource(def.resourcePath());
                    if (content != null) {
                        profile.setContent(content);
                        profile.setVersion(def.version());
                        profile.setDescription(def.description());
                        profileRepository.save(profile);
                        updated++;
                        log.info("Updated onboarding profile '{}' to version {}", def.key(), def.version());
                    }
                }
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
                    .version(def.version())
                    .build();

            profileRepository.save(profile);
            seeded++;
        }

        if (seeded > 0) {
            log.info("Seeded {} onboarding profiles", seeded);
        }
        if (updated > 0) {
            log.info("Updated {} onboarding profiles", updated);
            refreshEnabledProjects();
        }
    }

    private void refreshEnabledProjects() {
        try {
            List<Project> projects = projectService.findAll();
            int refreshed = 0;
            for (Project project : projects) {
                if (project.getConfig() != null) {
                    Object onboarding = project.getConfig().get("onboarding");
                    if (onboarding instanceof Map<?, ?> map && Boolean.TRUE.equals(map.get("enabled"))) {
                        projectOnboardingService.refresh(project);
                        refreshed++;
                    }
                }
            }
            if (refreshed > 0) {
                log.info("Refreshed onboarding files for {} projects", refreshed);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh onboarding files: {}", e.getMessage());
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
            String resourcePath, OnboardingProfileType type, int sortOrder, int version
    ) {}
}
