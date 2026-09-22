package com.automation.service.project;

import com.automation.config.OnboardingProfileSeeder;
import com.automation.dto.request.CreateOnboardingProfileRequest;
import com.automation.dto.request.UpdateOnboardingProfileRequest;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import com.automation.exception.ResourceNotFoundException;
import com.automation.repository.OnboardingProfileHistoryRepository;
import com.automation.repository.OnboardingProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OnboardingProfileServiceTest {

    @Mock private OnboardingProfileRepository profileRepository;
    @Mock private OnboardingProfileHistoryRepository historyRepository;
    @Mock private OnboardingProfileSeeder seeder;

    private OnboardingProfileService service;

    @BeforeEach
    void setUp() {
        service = new OnboardingProfileService(profileRepository, historyRepository, seeder);
    }

    @Nested
    class FindMethods {

        @Test
        void findAll_returnsSortedProfiles() {
            OnboardingProfile p1 = OnboardingProfile.builder().key("a").sortOrder(0).build();
            OnboardingProfile p2 = OnboardingProfile.builder().key("b").sortOrder(1).build();
            when(profileRepository.findAllByOrderBySortOrderAsc()).thenReturn(List.of(p1, p2));

            List<OnboardingProfile> result = service.findAll();

            assertThat(result).hasSize(2);
        }

        @Test
        void findById_found_returnsProfile() {
            OnboardingProfile profile = OnboardingProfile.builder().id("p1").key("base").build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

            OnboardingProfile result = service.findById("p1");

            assertThat(result.getKey()).isEqualTo("base");
        }

        @Test
        void findById_notFound_throwsException() {
            when(profileRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.findById("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Nested
    class Create {

        @Test
        void create_validRequest_createsProfile() {
            when(profileRepository.findByKey("new-key")).thenReturn(Optional.empty());
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new CreateOnboardingProfileRequest(
                    "new-key", Map.of("de", "Neu"), Map.of(), Map.of("de", "content"), OnboardingProfileType.PROFILE);

            OnboardingProfile result = service.create(request);

            assertThat(result.getKey()).isEqualTo("new-key");
            assertThat(result.isBuiltIn()).isFalse();
            assertThat(result.getVersion()).isEqualTo(1);
            assertThat(result.getSortOrder()).isEqualTo(100);
        }

        @Test
        void create_duplicateKey_throwsException() {
            when(profileRepository.findByKey("existing")).thenReturn(Optional.of(
                    OnboardingProfile.builder().key("existing").build()));

            var request = new CreateOnboardingProfileRequest(
                    "existing", Map.of(), Map.of(), Map.of("de", "content"), OnboardingProfileType.PROFILE);

            assertThatThrownBy(() -> service.create(request))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("already exists");
        }
    }

    @Nested
    class Update {

        @Test
        void update_contentChanged_createsHistoryAndIncrementsVersion() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").content(Map.of("de", "old content")).version(2).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateOnboardingProfileRequest(null, null, Map.of("de", "new content"));

            OnboardingProfile result = service.update("p1", request);

            assertThat(result.getContent()).isEqualTo(Map.of("de", "new content"));
            assertThat(result.getVersion()).isEqualTo(3);
            verify(historyRepository).save(any(OnboardingProfileHistory.class));
        }

        @Test
        void update_contentUnchanged_noHistoryNoVersionBump() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").content(Map.of("de", "same")).version(2).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateOnboardingProfileRequest(Map.of("de", "Neuer Name"), null, Map.of("de", "same"));

            OnboardingProfile result = service.update("p1", request);

            assertThat(result.getVersion()).isEqualTo(2); // not incremented
            assertThat(result.getName()).containsEntry("de", "Neuer Name");
            verify(historyRepository, never()).save(any());
        }

        @Test
        void update_onlyName_noHistoryCreated() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").content(Map.of("de", "content")).version(1).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateOnboardingProfileRequest(Map.of("de", "Aktualisiert"), null, null);

            OnboardingProfile result = service.update("p1", request);

            assertThat(result.getVersion()).isEqualTo(1);
            verify(historyRepository, never()).save(any());
        }

        @Test
        void update_historyContainsOldContent() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").content(Map.of("de", "old")).version(5).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            var request = new UpdateOnboardingProfileRequest(null, null, Map.of("de", "new"));
            service.update("p1", request);

            ArgumentCaptor<OnboardingProfileHistory> captor = ArgumentCaptor.forClass(OnboardingProfileHistory.class);
            verify(historyRepository).save(captor.capture());
            assertThat(captor.getValue().getContent()).isEqualTo(Map.of("de", "old"));
            assertThat(captor.getValue().getVersion()).isEqualTo(5);
        }
    }

    @Nested
    class Delete {

        @Test
        void delete_customProfile_deletesSuccessfully() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("custom").builtIn(false).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

            service.delete("p1");

            verify(historyRepository).deleteByProfileId("p1");
            verify(profileRepository).delete(profile);
        }

        @Test
        void delete_builtInProfile_throwsException() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").builtIn(true).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

            assertThatThrownBy(() -> service.delete("p1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Built-in");
        }
    }

    @Nested
    class ResetToDefault {

        @Test
        void reset_builtInProfile_resetsContent() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").builtIn(true).content(Map.of("de", "modified")).version(3).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(seeder.getDefaultContent("base")).thenReturn(Map.of("de", "default content"));
            when(profileRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            OnboardingProfile result = service.resetToDefault("p1");

            assertThat(result.getContent()).isEqualTo(Map.of("de", "default content"));
            assertThat(result.getVersion()).isEqualTo(4);
            verify(historyRepository).save(any(OnboardingProfileHistory.class));
        }

        @Test
        void reset_alreadyDefault_noChange() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("base").builtIn(true).content(Map.of("de", "default content")).version(1).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(seeder.getDefaultContent("base")).thenReturn(Map.of("de", "default content"));

            OnboardingProfile result = service.resetToDefault("p1");

            assertThat(result.getVersion()).isEqualTo(1);
            verify(historyRepository, never()).save(any());
            verify(profileRepository, never()).save(any());
        }

        @Test
        void reset_customProfile_throwsException() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("custom").builtIn(false).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));

            assertThatThrownBy(() -> service.resetToDefault("p1"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("built-in");
        }

        @Test
        void reset_noDefaultContent_throwsException() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("p1").key("unknown").builtIn(true).build();
            when(profileRepository.findById("p1")).thenReturn(Optional.of(profile));
            when(seeder.getDefaultContent("unknown")).thenReturn(null);

            assertThatThrownBy(() -> service.resetToDefault("p1"))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("No default content");
        }
    }

    @Nested
    class History {

        @Test
        void getHistory_returnsHistoryForProfile() {
            when(profileRepository.findById("p1")).thenReturn(Optional.of(
                    OnboardingProfile.builder().id("p1").build()));
            when(historyRepository.findByProfileIdOrderByVersionDesc("p1"))
                    .thenReturn(List.of(OnboardingProfileHistory.builder().version(2).build()));

            List<OnboardingProfileHistory> result = service.getHistory("p1");

            assertThat(result).hasSize(1);
        }

        @Test
        void getHistory_profileNotFound_throwsException() {
            when(profileRepository.findById("missing")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> service.getHistory("missing"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        @Test
        void getHistoryCount_returnsCount() {
            when(historyRepository.countByProfileId("p1")).thenReturn(5);

            assertThat(service.getHistoryCount("p1")).isEqualTo(5);
        }
    }
}
