package com.automation.service.project;

import com.automation.config.OnboardingProfileSeeder;
import com.automation.dto.request.CreateOnboardingProfileRequest;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingProfileService {

    private final OnboardingProfileRepository profileRepository;
    private final OnboardingProfileHistoryRepository historyRepository;
    private final OnboardingProfileSeeder seeder;
    private final ApplicationEventPublisher eventPublisher;

    @Transactional(readOnly = true)
    public List<OnboardingProfile> findAll() {
        return profileRepository.findAllByOrderBySortOrderAsc();
    }

    @Transactional(readOnly = true)
    public OnboardingProfile findById(String id) {
        return profileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("OnboardingProfile", id));
    }

    @Transactional
    public OnboardingProfile create(CreateOnboardingProfileRequest request) {
        if (profileRepository.findByKey(request.key()).isPresent()) {
            throw new IllegalArgumentException("Profile with key '" + request.key() + "' already exists");
        }

        Map<String, String> content = mergeContent(Map.of(), request.content());
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Profile content needs at least one language");
        }

        OnboardingProfile profile = OnboardingProfile.builder()
                .key(request.key())
                .name(request.name())
                .description(request.description())
                .content(content)
                .type(request.type())
                .builtIn(false)
                .sortOrder(100)
                .version(1)
                .build();

        OnboardingProfile saved = profileRepository.save(profile);
        log.info("Created onboarding profile: {} ({})", saved.getKey(), saved.getId());
        return saved;
    }

    @Transactional
    public OnboardingProfile update(String id, UpdateOnboardingProfileRequest request) {
        OnboardingProfile profile = findById(id);

        Map<String, String> content = request.content() == null
                ? profile.getContent()
                : mergeContent(profile.getContent(), request.content());
        if (content.isEmpty()) {
            throw new IllegalArgumentException("Profile content needs at least one language");
        }
        boolean contentChanged = !content.equals(profile.getContent());

        if (contentChanged) {
            OnboardingProfileHistory history = OnboardingProfileHistory.builder()
                    .profileId(profile.getId())
                    .version(profile.getVersion())
                    .content(profile.getContent())
                    .changedBy("user")
                    .changedAt(Instant.now())
                    .build();
            historyRepository.save(history);
        }

        if (request.name() != null) {
            profile.setName(request.name());
        }
        if (request.description() != null) {
            profile.setDescription(request.description());
        }
        if (contentChanged) {
            profile.setContent(content);
            profile.setVersion(profile.getVersion() + 1);
            profile.setCustomized(true);
        }

        OnboardingProfile saved = profileRepository.save(profile);
        log.info("Updated onboarding profile: {} (v{})", saved.getKey(), saved.getVersion());
        if (contentChanged) {
            publishChanged(saved);
        }
        return saved;
    }

    @Transactional
    public void delete(String id) {
        OnboardingProfile profile = findById(id);
        if (profile.isBuiltIn()) {
            throw new IllegalArgumentException("Built-in profiles cannot be deleted");
        }
        historyRepository.deleteByProfileId(id);
        profileRepository.delete(profile);
        log.info("Deleted onboarding profile: {}", profile.getKey());
        publishChanged(profile);
    }

    @Transactional
    public OnboardingProfile resetToDefault(String id) {
        OnboardingProfile profile = findById(id);
        if (!profile.isBuiltIn()) {
            throw new IllegalArgumentException("Only built-in profiles can be reset");
        }

        Map<String, String> defaultContent = seeder.getDefaultContent(profile.getKey());
        if (defaultContent == null || defaultContent.isEmpty()) {
            throw new IllegalStateException("No default content found for profile: " + profile.getKey());
        }

        int shippedVersion = seeder.shippedVersion(profile.getKey());
        if (defaultContent.equals(profile.getContent())) {
            if (profile.isCustomized() || profile.getShippedVersion() != shippedVersion) {
                profile.setCustomized(false);
                profile.setShippedVersion(shippedVersion);
                return profileRepository.save(profile);
            }
            return profile;
        }

        OnboardingProfileHistory history = OnboardingProfileHistory.builder()
                .profileId(profile.getId())
                .version(profile.getVersion())
                .content(profile.getContent())
                .changedBy("user")
                .changedAt(Instant.now())
                .build();
        historyRepository.save(history);

        profile.setContent(defaultContent);
        profile.setVersion(profile.getVersion() + 1);
        profile.setCustomized(false);
        profile.setShippedVersion(shippedVersion);

        OnboardingProfile saved = profileRepository.save(profile);
        log.info("Reset onboarding profile to default: {} (v{})", saved.getKey(), saved.getVersion());
        publishChanged(saved);
        return saved;
    }

    /** Whether a newer shipped text exists for this built-in profile than the one it is based on. */
    public boolean isUpdateAvailable(OnboardingProfile profile) {
        return seeder.isUpdateAvailable(profile);
    }

    /** The current shipped text of a built-in profile (to compare before taking it over). */
    public Map<String, String> shippedContent(OnboardingProfile profile) {
        Map<String, String> shipped = profile.isBuiltIn() ? seeder.getDefaultContent(profile.getKey()) : null;
        if (shipped == null || shipped.isEmpty()) {
            throw new ResourceNotFoundException("Shipped onboarding text", profile.getKey());
        }
        return shipped;
    }

    public int shippedVersion(OnboardingProfile profile) {
        return seeder.shippedVersion(profile.getKey());
    }

    /** Project files that include the profile are written again once the change is committed. */
    private void publishChanged(OnboardingProfile profile) {
        eventPublisher.publishEvent(new OnboardingProfileChangedEvent(
                profile.getId(), profile.getType() == com.automation.entity.OnboardingProfileType.BASE));
    }

    /**
     * The stored content with the sent languages applied: a filled value replaces that language, a
     * blank one removes it; languages not sent stay as they are.
     */
    static Map<String, String> mergeContent(Map<String, String> current, Map<String, String> changes) {
        Map<String, String> merged = new HashMap<>();
        if (current != null) {
            current.forEach((lang, text) -> {
                if (text != null && !text.isBlank()) {
                    merged.put(lang, text);
                }
            });
        }
        if (changes != null) {
            changes.forEach((lang, text) -> {
                if (text == null || text.isBlank()) {
                    merged.remove(lang);
                } else {
                    merged.put(lang, text);
                }
            });
        }
        return merged;
    }

    @Transactional(readOnly = true)
    public List<OnboardingProfileHistory> getHistory(String profileId) {
        findById(profileId);
        return historyRepository.findByProfileIdOrderByVersionDesc(profileId);
    }

    @Transactional(readOnly = true)
    public int getHistoryCount(String profileId) {
        return historyRepository.countByProfileId(profileId);
    }
}
