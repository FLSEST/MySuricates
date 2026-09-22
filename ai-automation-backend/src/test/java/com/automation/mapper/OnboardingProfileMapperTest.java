package com.automation.mapper;

import com.automation.dto.response.OnboardingProfileHistoryResponse;
import com.automation.dto.response.OnboardingProfileResponse;
import com.automation.entity.OnboardingProfile;
import com.automation.entity.OnboardingProfileHistory;
import com.automation.entity.OnboardingProfileType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileMapperTest {

    private OnboardingProfileMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new OnboardingProfileMapperImpl();
    }

    @Nested
    class ToResponse {

        @Test
        void mapsAllFields() {
            Instant now = Instant.now();
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("op-1")
                    .key("default")
                    .name(Map.of("de", "Standard", "en", "Default"))
                    .description(Map.of("de", "Standard-Profil"))
                    .content(Map.of("de", "# Welcome\nThis is the content"))
                    .type(OnboardingProfileType.PROFILE)
                    .builtIn(true)
                    .sortOrder(1)
                    .version(3)
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            OnboardingProfileResponse response = mapper.toResponse(profile);

            assertThat(response.id()).isEqualTo("op-1");
            assertThat(response.key()).isEqualTo("default");
            assertThat(response.name()).containsEntry("de", "Standard").containsEntry("en", "Default");
            assertThat(response.description()).containsEntry("de", "Standard-Profil");
            assertThat(response.content()).isEqualTo(Map.of("de", "# Welcome\nThis is the content"));
            assertThat(response.type()).isEqualTo(OnboardingProfileType.PROFILE);
            assertThat(response.builtIn()).isTrue();
            assertThat(response.sortOrder()).isEqualTo(1);
            assertThat(response.version()).isEqualTo(3);
            assertThat(response.createdAt()).isEqualTo(now);
            assertThat(response.updatedAt()).isEqualTo(now);
        }

        @Test
        void historyCount_alwaysZero() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("op-2")
                    .key("custom")
                    .content(Map.of("de", "content"))
                    .type(OnboardingProfileType.PROFILE)
                    .build();

            OnboardingProfileResponse response = mapper.toResponse(profile);

            assertThat(response.historyCount()).isEqualTo(0);
        }

        @Test
        void nullOptionalFields_mappedAsNull() {
            OnboardingProfile profile = OnboardingProfile.builder()
                    .id("op-3")
                    .key("minimal")
                    .content(Map.of("de", "content"))
                    .type(OnboardingProfileType.PROFILE)
                    .build();

            OnboardingProfileResponse response = mapper.toResponse(profile);

            assertThat(response.createdAt()).isNull();
            assertThat(response.updatedAt()).isNull();
        }
    }

    @Test
    void toResponseList_mapsMultiple() {
        OnboardingProfile p1 = OnboardingProfile.builder().id("p1").key("k1").content(Map.of("de", "c1")).type(OnboardingProfileType.PROFILE).build();
        OnboardingProfile p2 = OnboardingProfile.builder().id("p2").key("k2").content(Map.of("de", "c2")).type(OnboardingProfileType.PROFILE).build();

        List<OnboardingProfileResponse> responses = mapper.toResponseList(List.of(p1, p2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("p1");
        assertThat(responses.get(1).id()).isEqualTo("p2");
    }

    @Nested
    class ToHistoryResponse {

        @Test
        void mapsAllFields() {
            Instant changedAt = Instant.now();
            OnboardingProfileHistory history = OnboardingProfileHistory.builder()
                    .id("h-1")
                    .version(2)
                    .content(Map.of("de", "old content"))
                    .changedBy("admin")
                    .changedAt(changedAt)
                    .build();

            OnboardingProfileHistoryResponse response = mapper.toHistoryResponse(history);

            assertThat(response.id()).isEqualTo("h-1");
            assertThat(response.version()).isEqualTo(2);
            assertThat(response.content()).isEqualTo(Map.of("de", "old content"));
            assertThat(response.changedBy()).isEqualTo("admin");
            assertThat(response.changedAt()).isEqualTo(changedAt);
        }
    }

    @Test
    void toHistoryResponseList_mapsMultiple() {
        OnboardingProfileHistory h1 = OnboardingProfileHistory.builder().id("h1").version(1).content(Map.of("de", "c1")).changedBy("user").changedAt(Instant.now()).build();
        OnboardingProfileHistory h2 = OnboardingProfileHistory.builder().id("h2").version(2).content(Map.of("de", "c2")).changedBy("admin").changedAt(Instant.now()).build();

        List<OnboardingProfileHistoryResponse> responses = mapper.toHistoryResponseList(List.of(h1, h2));

        assertThat(responses).hasSize(2);
        assertThat(responses.get(0).id()).isEqualTo("h1");
        assertThat(responses.get(1).id()).isEqualTo("h2");
    }
}
