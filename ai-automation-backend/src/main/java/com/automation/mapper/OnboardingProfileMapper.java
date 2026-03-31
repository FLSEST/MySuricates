package com.automation.mapper;

import com.automation.dto.response.OnboardingProfileHistoryResponse;
import com.automation.dto.response.OnboardingProfileResponse;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

import java.util.List;

@Mapper(componentModel = "spring")
public interface OnboardingProfileMapper {

    @Mapping(target = "historyCount", ignore = true)
    OnboardingProfileResponse toResponse(OnboardingProfile profile);

    List<OnboardingProfileResponse> toResponseList(List<OnboardingProfile> profiles);

    OnboardingProfileHistoryResponse toHistoryResponse(OnboardingProfileHistory history);

    List<OnboardingProfileHistoryResponse> toHistoryResponseList(List<OnboardingProfileHistory> histories);
}
