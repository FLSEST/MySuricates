package com.automation.controller;

import com.automation.dto.request.CreateOnboardingProfileRequest;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.dto.response.OnboardingProfileHistoryResponse;
import com.automation.dto.response.OnboardingProfileResponse;
import com.automation.dto.response.ShippedOnboardingProfileResponse;
import com.automation.entity.OnboardingProfile;
import com.automation.mapper.OnboardingProfileMapper;
import com.automation.modules.ModuleCatalog;
import com.automation.modules.RequiresModule;
import com.automation.service.project.OnboardingProfileService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** Onboarding profiles: the built-in and custom texts a project's onboarding file for AI agents is assembled from. */
@RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
@RestController
@RequestMapping("/api/v1/onboarding-profiles")
@RequiredArgsConstructor
public class OnboardingProfileController {

    private final OnboardingProfileService profileService;
    private final OnboardingProfileMapper profileMapper;

    /** Lists all onboarding profiles in their sort order. */
    @GetMapping
    public List<OnboardingProfileResponse> listProfiles() {
        List<OnboardingProfile> profiles = profileService.findAll();
        return profiles.stream()
                .map(this::toResponseWithHistory)
                .toList();
    }

    /** Returns one onboarding profile. */
    @GetMapping("/{id}")
    public OnboardingProfileResponse getProfile(@PathVariable String id) {
        return toResponseWithHistory(profileService.findById(id));
    }

    /**
     * Creates a custom profile (201); {@code key} (lowercase letters, digits, dashes), {@code name}, {@code content} and {@code type} are required.
     * {@code content} is Markdown per language and needs at least one filled language; a taken {@code key} is a 400.
     */
    @PostMapping
    public ResponseEntity<OnboardingProfileResponse> createProfile(@Valid @RequestBody CreateOnboardingProfileRequest request) {
        OnboardingProfile profile = profileService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithHistory(profile));
    }

    /**
     * Updates name, description or content of a profile; only the languages sent in {@code content} are replaced.
     * A content change keeps the old text in the history and rewrites the onboarding files of the projects using it.
     */
    @PutMapping("/{id}")
    public OnboardingProfileResponse updateProfile(@PathVariable String id, @RequestBody UpdateOnboardingProfileRequest request) {
        return toResponseWithHistory(profileService.update(id, request));
    }

    /** Deletes a custom profile and its history (204); built-in profiles cannot be deleted (400). */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String id) {
        profileService.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Sets a built-in profile back to the current shipped text; the previous text goes into the history. */
    @PostMapping("/{id}/reset")
    public OnboardingProfileResponse resetProfile(@PathVariable String id) {
        return toResponseWithHistory(profileService.resetToDefault(id));
    }

    /** The current shipped text of a built-in profile, to compare with an edited one before taking it over. */
    @GetMapping("/{id}/shipped")
    public ShippedOnboardingProfileResponse getShipped(@PathVariable String id) {
        OnboardingProfile profile = profileService.findById(id);
        return new ShippedOnboardingProfileResponse(profileService.shippedVersion(profile), profileService.shippedContent(profile));
    }

    /** Lists the earlier versions of a profile's content, newest first. */
    @GetMapping("/{id}/history")
    public List<OnboardingProfileHistoryResponse> getHistory(@PathVariable String id) {
        return profileMapper.toHistoryResponseList(profileService.getHistory(id));
    }

    private OnboardingProfileResponse toResponseWithHistory(OnboardingProfile profile) {
        OnboardingProfileResponse base = profileMapper.toResponse(profile);
        int historyCount = profileService.getHistoryCount(profile.getId());
        return new OnboardingProfileResponse(
                base.id(), base.key(), base.name(), base.description(),
                base.content(), base.type(), base.builtIn(), base.sortOrder(),
                base.version(), historyCount, profile.isBuiltIn() && profile.isCustomized(),
                profileService.isUpdateAvailable(profile), base.createdAt(), base.updatedAt()
        );
    }
}
