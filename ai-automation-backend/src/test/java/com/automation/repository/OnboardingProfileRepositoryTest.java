package com.automation.repository;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class OnboardingProfileRepositoryTest {

    @Autowired
    private OnboardingProfileRepository repository;

    @BeforeEach
    void setUp() {
        repository.deleteAll();
    }

    private OnboardingProfile profile(String key, OnboardingProfileType type, boolean builtIn, int sortOrder) {
        return OnboardingProfile.builder()
                .key(key)
                .name(Map.of("en", "Name " + key))
                .content("Content for " + key)
                .type(type)
                .builtIn(builtIn)
                .sortOrder(sortOrder)
                .build();
    }

    // --- findByKey ---

    @Test
    void should_returnProfile_when_keyExists() {
        repository.save(profile("test-key-exists", OnboardingProfileType.BASE, false, 0));

        Optional<OnboardingProfile> result = repository.findByKey("test-key-exists");

        assertThat(result).isPresent();
        assertThat(result.get().getKey()).isEqualTo("test-key-exists");
        assertThat(result.get().getType()).isEqualTo(OnboardingProfileType.BASE);
    }

    @Test
    void should_returnEmpty_when_keyDoesNotExist() {
        Optional<OnboardingProfile> result = repository.findByKey("nonexistent-key-xyz");

        assertThat(result).isEmpty();
    }

    // --- findByBuiltInTrue ---

    @Test
    void should_returnOnlyBuiltInProfiles_when_builtInProfilesExist() {
        repository.save(profile("builtin-a", OnboardingProfileType.BASE, true, 0));
        repository.save(profile("builtin-b", OnboardingProfileType.PROFILE, true, 1));
        repository.save(profile("custom-a", OnboardingProfileType.PROFILE, false, 2));

        List<OnboardingProfile> result = repository.findByBuiltInTrue();

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result).allMatch(OnboardingProfile::isBuiltIn);
        assertThat(result).extracting(OnboardingProfile::getKey)
                .contains("builtin-a", "builtin-b")
                .doesNotContain("custom-a");
    }

    @Test
    void should_returnEmptyList_when_noBuiltInProfilesExist() {
        repository.save(profile("custom-only-x", OnboardingProfileType.BASE, false, 0));

        List<OnboardingProfile> result = repository.findByBuiltInTrue();

        assertThat(result).isEmpty();
    }

    // --- findByType ---

    @Test
    void should_returnProfilesMatchingType_when_typeExists() {
        repository.save(profile("type-base-1", OnboardingProfileType.BASE, false, 0));
        repository.save(profile("type-base-2", OnboardingProfileType.BASE, false, 1));
        repository.save(profile("type-profile-1", OnboardingProfileType.PROFILE, false, 2));

        List<OnboardingProfile> result = repository.findByType(OnboardingProfileType.BASE);

        assertThat(result).hasSizeGreaterThanOrEqualTo(2);
        assertThat(result).allMatch(p -> p.getType() == OnboardingProfileType.BASE);
        assertThat(result).extracting(OnboardingProfile::getKey)
                .contains("type-base-1", "type-base-2")
                .doesNotContain("type-profile-1");
    }

    @Test
    void should_returnEmptyList_when_noProfilesMatchType() {
        repository.save(profile("type-base-only", OnboardingProfileType.BASE, false, 0));

        List<OnboardingProfile> result = repository.findByType(OnboardingProfileType.PROFILE);

        assertThat(result).isEmpty();
    }

    // --- findAllByOrderBySortOrderAsc ---

    @Test
    void should_returnProfilesInAscendingSortOrder_when_multipleProfilesExist() {
        repository.save(profile("sort-last", OnboardingProfileType.BASE, false, 300));
        repository.save(profile("sort-first", OnboardingProfileType.BASE, false, 100));
        repository.save(profile("sort-middle", OnboardingProfileType.BASE, false, 200));

        List<OnboardingProfile> result = repository.findAllByOrderBySortOrderAsc();

        List<String> keys = result.stream().map(OnboardingProfile::getKey).toList();
        int idxFirst = keys.indexOf("sort-first");
        int idxMiddle = keys.indexOf("sort-middle");
        int idxLast = keys.indexOf("sort-last");

        assertThat(idxFirst).isGreaterThanOrEqualTo(0);
        assertThat(idxFirst).isLessThan(idxMiddle);
        assertThat(idxMiddle).isLessThan(idxLast);
    }

    @Test
    void should_returnEmptyList_when_noProfilesExist() {
        List<OnboardingProfile> result = repository.findAllByOrderBySortOrderAsc();

        assertThat(result).isEmpty();
    }
}
