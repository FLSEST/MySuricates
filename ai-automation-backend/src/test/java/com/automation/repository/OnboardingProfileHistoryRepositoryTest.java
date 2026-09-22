package com.automation.repository;

import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("integration")
@Transactional
class OnboardingProfileHistoryRepositoryTest {

    @Autowired
    private OnboardingProfileHistoryRepository repository;

    @Autowired
    private OnboardingProfileRepository profileRepository;

    @Test
    void should_return_history_entries_ordered_by_version_desc_when_findByProfileIdOrderByVersionDesc_called() {
        String profileId = saveProfile("profile-1").getId();
        saveHistory(profileId, 1, "v1");
        saveHistory(profileId, 3, "v3");
        saveHistory(profileId, 2, "v2");

        List<OnboardingProfileHistory> result = repository.findByProfileIdOrderByVersionDesc(profileId);

        assertThat(result).hasSize(3);
        assertThat(result).extracting(OnboardingProfileHistory::getVersion).containsExactly(3, 2, 1);
        assertThat(result).extracting(OnboardingProfileHistory::getContent)
                .containsExactly(Map.of("de", "v3"), Map.of("de", "v2"), Map.of("de", "v1"));
    }

    @Test
    void should_return_only_matching_profile_entries_when_findByProfileIdOrderByVersionDesc_called() {
        String profileA = saveProfile("profile-A").getId();
        String profileB = saveProfile("profile-B").getId();
        saveHistory(profileA, 1, "a1");
        saveHistory(profileA, 2, "a2");
        saveHistory(profileB, 1, "b1");

        List<OnboardingProfileHistory> result = repository.findByProfileIdOrderByVersionDesc(profileA);

        assertThat(result).hasSize(2);
        assertThat(result).allMatch(h -> profileA.equals(h.getProfileId()));
    }

    @Test
    void should_return_empty_list_when_findByProfileIdOrderByVersionDesc_with_unknown_id() {
        String profileId = saveProfile("profile-X").getId();
        saveHistory(profileId, 1, "x1");

        List<OnboardingProfileHistory> result = repository.findByProfileIdOrderByVersionDesc("does-not-exist");

        assertThat(result).isEmpty();
    }

    @Test
    void should_return_correct_count_when_countByProfileId_called() {
        String profileId = saveProfile("profile-count").getId();
        String otherId = saveProfile("other-profile").getId();
        saveHistory(profileId, 1, "c1");
        saveHistory(profileId, 2, "c2");
        saveHistory(profileId, 3, "c3");
        saveHistory(otherId, 1, "o1");

        int count = repository.countByProfileId(profileId);

        assertThat(count).isEqualTo(3);
    }

    @Test
    void should_return_zero_when_countByProfileId_with_unknown_id() {
        int count = repository.countByProfileId("unknown");

        assertThat(count).isZero();
    }

    @Test
    void should_delete_only_matching_entries_when_deleteByProfileId_called() {
        String deleteId = saveProfile("profile-delete").getId();
        String keepId = saveProfile("profile-keep").getId();
        saveHistory(deleteId, 1, "d1");
        saveHistory(deleteId, 2, "d2");
        saveHistory(keepId, 1, "k1");

        repository.deleteByProfileId(deleteId);
        repository.flush();

        assertThat(repository.countByProfileId(deleteId)).isZero();
        assertThat(repository.countByProfileId(keepId)).isEqualTo(1);
    }

    @Test
    void should_not_throw_when_deleteByProfileId_with_unknown_id() {
        String existingId = saveProfile("profile-existing").getId();
        saveHistory(existingId, 1, "e1");

        repository.deleteByProfileId("does-not-exist");
        repository.flush();

        assertThat(repository.countByProfileId(existingId)).isEqualTo(1);
    }

    private OnboardingProfile saveProfile(String key) {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key(key)
                .name(Map.of("de", key))
                .content(Map.of("de", "{}"))
                .type(OnboardingProfileType.PROFILE)
                .build();
        return profileRepository.saveAndFlush(profile);
    }

    private OnboardingProfileHistory saveHistory(String profileId, int version, String content) {
        OnboardingProfileHistory history = OnboardingProfileHistory.builder()
                .profileId(profileId)
                .version(version)
                .content(Map.of("de", content))
                .changedBy("test-user")
                .changedAt(Instant.now())
                .build();
        return repository.saveAndFlush(history);
    }
}
