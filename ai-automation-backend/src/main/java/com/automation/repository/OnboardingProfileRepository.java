package com.automation.repository;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface OnboardingProfileRepository extends JpaRepository<OnboardingProfile, String> {

    Optional<OnboardingProfile> findByKey(String key);

    List<OnboardingProfile> findByBuiltInTrue();

    List<OnboardingProfile> findByType(OnboardingProfileType type);

    List<OnboardingProfile> findAllByOrderBySortOrderAsc();
}
