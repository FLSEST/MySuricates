package com.automation.repository;

import com.automation.entity.OnboardingProfileHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OnboardingProfileHistoryRepository extends JpaRepository<OnboardingProfileHistory, String> {

    List<OnboardingProfileHistory> findByProfileIdOrderByVersionDesc(String profileId);

    int countByProfileId(String profileId);

    void deleteByProfileId(String profileId);
}
