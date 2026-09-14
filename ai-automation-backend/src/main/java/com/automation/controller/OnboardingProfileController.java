package com.automation.controller;

import com.automation.dto.request.CreateOnboardingProfileRequest;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.dto.response.OnboardingProfileHistoryResponse;
import com.automation.dto.response.OnboardingProfileResponse;
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

@RequiresModule(ModuleCatalog.ONBOARDING_PROFILES)
@RestController
@RequestMapping("/api/v1/onboarding-profiles")
@RequiredArgsConstructor
public class OnboardingProfileController {

    private final OnboardingProfileService profileService;
    private final OnboardingProfileMapper profileMapper;

    @GetMapping
    public List<OnboardingProfileResponse> listProfiles() {
        List<OnboardingProfile> profiles = profileService.findAll();
        return profiles.stream()
                .map(this::toResponseWithHistory)
                .toList();
    }

    @GetMapping("/{id}")
    public OnboardingProfileResponse getProfile(@PathVariable String id) {
        return toResponseWithHistory(profileService.findById(id));
    }

    @PostMapping
    public ResponseEntity<OnboardingProfileResponse> createProfile(@Valid @RequestBody CreateOnboardingProfileRequest request) {
        OnboardingProfile profile = profileService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponseWithHistory(profile));
    }

    @PutMapping("/{id}")
    public OnboardingProfileResponse updateProfile(@PathVariable String id, @RequestBody UpdateOnboardingProfileRequest request) {
        return toResponseWithHistory(profileService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProfile(@PathVariable String id) {
        profileService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/reset")
    public OnboardingProfileResponse resetProfile(@PathVariable String id) {
        return toResponseWithHistory(profileService.resetToDefault(id));
    }

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
                base.version(), historyCount, base.createdAt(), base.updatedAt()
        );
    }
}
