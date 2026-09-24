package com.automation.service.project;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import com.automation.repository.OnboardingProfileHistoryRepository;
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
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class OnboardingProfileSeeder implements ApplicationRunner {

    private final OnboardingProfileRepository profileRepository;
    private final OnboardingProfileHistoryRepository historyRepository;
    private final ProjectOnboardingService projectOnboardingService;

    /** Languages the built-in texts are shipped in: {@code onboarding/<lang>/<file>}. */
    static final List<String> LANGUAGES = List.of("de", "en");

    // Shipped versions: raise one to ship a changed text; unchanged profiles take it over, edited ones
    // show "update available". Base v4 / platform v2 = bilingual rewrite (TODO a805d0a7), the other
    // profiles v4 = module sections (TODO 644fd5ad).
    private static final List<ProfileDefinition> BUILT_IN_PROFILES = List.of(
            new ProfileDefinition("onboarding-base", "base",
                    Map.of("de", "Basis", "en", "Base"),
                    Map.of("de", "MySuricates API-Referenz und Grundregeln", "en", "MySuricates API reference and basic rules"),
                    "base.md", OnboardingProfileType.BASE, 0, 4),
            new ProfileDefinition("onboarding-developer", "developer",
                    Map.of("de", "Entwickler", "en", "Developer"),
                    Map.of("de", "Code-Review, Test-Pipelines, Auto-Fix, Quality Gates, vollständige API-Referenz", "en", "Code review, test pipelines, auto-fix, quality gates, full API reference"),
                    "profiles/developer.md", OnboardingProfileType.PROFILE, 1, 4),
            new ProfileDefinition("onboarding-research", "research",
                    Map.of("de", "Forschung", "en", "Research"),
                    Map.of("de", "Analyse-Workflows, Zusammenfassungen, Daten-Pipelines, Run-History, Workflow Memory", "en", "Analysis workflows, summaries, data pipelines, run history, workflow memory"),
                    "profiles/research.md", OnboardingProfileType.PROFILE, 2, 4),
            new ProfileDefinition("onboarding-content", "content",
                    Map.of("de", "Content", "en", "Content"),
                    Map.of("de", "Content-Generierung, Review, Batch-Verarbeitung, Run-History", "en", "Content generation, review, batch processing, run history"),
                    "profiles/content.md", OnboardingProfileType.PROFILE, 3, 4),
            new ProfileDefinition("onboarding-ops", "ops",
                    Map.of("de", "Operations", "en", "Operations"),
                    Map.of("de", "Monitoring, Log-Analyse, Deployment-Chains, Schedules, Webhooks, Activity Log", "en", "Monitoring, log analysis, deployment chains, schedules, webhooks, activity log"),
                    "profiles/ops.md", OnboardingProfileType.PROFILE, 4, 4),
            new ProfileDefinition("onboarding-platform", "platform",
                    Map.of("de", "Plattform", "en", "Platform"),
                    Map.of("de", "Gate-Status, AutoTodo, TodoTrigger, Modelle, Variablen, Agentic-Safety", "en", "Gate status, auto-todo, todo triggers, models, variables, agentic safety"),
                    "profiles/platform.md", OnboardingProfileType.PROFILE, 5, 2)
    );

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int seeded = 0;
        int updated = 0;

        for (ProfileDefinition def : BUILT_IN_PROFILES) {
            var existing = profileRepository.findById(def.id());
            if (existing.isPresent()) {
                if (upgrade(existing.get(), def)) {
                    updated++;
                }
                continue;
            }
            if (profileRepository.findByKey(def.key()).isPresent()) {
                continue;
            }

            Map<String, String> content = loadContent(def.resourcePath());
            if (content.isEmpty()) {
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
                    .shippedVersion(def.version())
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

    /**
     * Brings a stored built-in profile to the shipped text (TODO 644fd5ad). An unchanged profile gets
     * the newer text (the old one goes into the history); an edited one keeps its text and shows
     * "update available" ({@link #isUpdateAvailable}).
     *
     * <p>Profiles from before V83 ({@code shippedVersion} 0) are classified once by their history:
     * the latest change made by hand ({@code changedBy "user"}) means edited. Comparing with the
     * shipped text alone would not do — a stored older shipped text differs from the current one too.
     *
     * @return whether the stored text was replaced
     */
    private boolean upgrade(OnboardingProfile profile, ProfileDefinition def) {
        Map<String, String> shipped = loadContent(def.resourcePath());
        if (shipped.isEmpty()) {
            return false;
        }
        if (profile.getShippedVersion() == 0) {
            if (shipped.equals(profile.getContent())) {
                profile.setCustomized(false);
                profile.setShippedVersion(def.version());
                profileRepository.save(profile);
                return false;
            }
            boolean editedByHand = historyRepository.findByProfileIdOrderByVersionDesc(profile.getId()).stream()
                    .findFirst()
                    .map(latest -> "user".equals(latest.getChangedBy()))
                    .orElse(false);
            profile.setCustomized(editedByHand);
            if (editedByHand) {
                profileRepository.save(profile);
                return false;
            }
        } else if (profile.getShippedVersion() >= def.version() || profile.isCustomized()) {
            return false;
        }
        historyRepository.save(OnboardingProfileHistory.builder()
                .profileId(profile.getId())
                .version(profile.getVersion())
                .content(profile.getContent())
                .changedBy("system")
                .changedAt(Instant.now())
                .build());
        profile.setContent(shipped);
        profile.setVersion(profile.getVersion() + 1);
        profile.setShippedVersion(def.version());
        profile.setDescription(def.description());
        profileRepository.save(profile);
        log.info("Updated onboarding profile '{}' to shipped version {}", def.key(), def.version());
        return true;
    }

    /** Version of the shipped text of a built-in profile; 0 for an unknown key. */
    public int shippedVersion(String key) {
        return BUILT_IN_PROFILES.stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .map(ProfileDefinition::version)
                .orElse(0);
    }

    /** A built-in profile whose stored text is based on an older shipped text than the current one. */
    public boolean isUpdateAvailable(OnboardingProfile profile) {
        return profile.isBuiltIn() && profile.getShippedVersion() < shippedVersion(profile.getKey());
    }

    private void refreshEnabledProjects() {
        try {
            // Only while the module is on (refreshEnabled checks it); files of a switched-off module stay as they are.
            int refreshed = projectOnboardingService.refreshEnabled(project -> true);
            if (refreshed > 0) {
                log.info("Refreshed onboarding files for {} projects", refreshed);
            }
        } catch (Exception e) {
            log.warn("Failed to refresh onboarding files: {}", e.getMessage());
        }
    }

    /** The shipped text of a built-in profile per language, or {@code null} for an unknown key. */
    public Map<String, String> getDefaultContent(String key) {
        return BUILT_IN_PROFILES.stream()
                .filter(d -> d.key().equals(key))
                .findFirst()
                .map(d -> loadContent(d.resourcePath()))
                .orElse(null);
    }

    private Map<String, String> loadContent(String file) {
        Map<String, String> content = new HashMap<>();
        for (String lang : LANGUAGES) {
            String text = loadResource("onboarding/" + lang + "/" + file);
            if (text != null) {
                content.put(lang, text);
            }
        }
        return content;
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
