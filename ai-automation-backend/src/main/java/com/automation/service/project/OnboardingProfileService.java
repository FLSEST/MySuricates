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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class OnboardingProfileService {

    private final OnboardingProfileRepository profileRepository;
    private final OnboardingProfileHistoryRepository historyRepository;
    private final OnboardingProfileSeeder seeder;

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

        OnboardingProfile profile = OnboardingProfile.builder()
                .key(request.key())
                .name(request.name())
                .description(request.description())
                .content(request.content())
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

        boolean contentChanged = request.content() != null && !request.content().equals(profile.getContent());

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
            profile.setContent(request.content());
            profile.setVersion(profile.getVersion() + 1);
        }

        OnboardingProfile saved = profileRepository.save(profile);
        log.info("Updated onboarding profile: {} (v{})", saved.getKey(), saved.getVersion());
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
    }

    @Transactional
    public OnboardingProfile resetToDefault(String id) {
        OnboardingProfile profile = findById(id);
        if (!profile.isBuiltIn()) {
            throw new IllegalArgumentException("Only built-in profiles can be reset");
        }

        String defaultContent = seeder.getDefaultContent(profile.getKey());
        if (defaultContent == null) {
            throw new IllegalStateException("No default content found for profile: " + profile.getKey());
        }

        if (defaultContent.equals(profile.getContent())) {
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

        OnboardingProfile saved = profileRepository.save(profile);
        log.info("Reset onboarding profile to default: {} (v{})", saved.getKey(), saved.getVersion());
        return saved;
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
