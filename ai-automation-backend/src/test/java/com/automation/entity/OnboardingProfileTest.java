package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileTest {

    @Test
    void should_generateUuid_when_idIsNull() throws Exception {
        OnboardingProfile profile = new OnboardingProfile();
        assertThat(profile.getId()).isNull();

        invokeEnsureId(profile);

        assertThat(profile.getId()).isNotNull().isNotBlank();
        assertThat(profile.getId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void should_generateUuid_when_idIsBlank() throws Exception {
        OnboardingProfile profile = new OnboardingProfile();
        profile.setId("   ");

        invokeEnsureId(profile);

        assertThat(profile.getId()).isNotBlank().doesNotContain("   ");
    }

    @Test
    void should_generateUuid_when_idIsEmptyString() throws Exception {
        OnboardingProfile profile = new OnboardingProfile();
        profile.setId("");

        invokeEnsureId(profile);

        assertThat(profile.getId()).isNotBlank();
        assertThat(profile.getId()).matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");
    }

    @Test
    void should_preserveExistingId_when_idIsNotNull() throws Exception {
        OnboardingProfile profile = new OnboardingProfile();
        profile.setId("existing-id-123");

        invokeEnsureId(profile);

        assertThat(profile.getId()).isEqualTo("existing-id-123");
    }

    @Test
    void should_generateDifferentUuids_when_ensureIdCalledOnMultipleInstances() throws Exception {
        OnboardingProfile profile1 = new OnboardingProfile();
        OnboardingProfile profile2 = new OnboardingProfile();

        invokeEnsureId(profile1);
        invokeEnsureId(profile2);

        assertThat(profile1.getId()).isNotEqualTo(profile2.getId());
    }

    @Test
    void should_initializeNameAsEmptyMap_when_usingBuilderDefaults() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("test-key")
                .content(Map.of("de", "test content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.getName()).isNotNull().isEmpty();
    }

    @Test
    void should_initializeDescriptionAsEmptyMap_when_usingBuilderDefaults() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("test-key")
                .content(Map.of("de", "test content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.getDescription()).isNotNull().isEmpty();
    }

    @Test
    void should_defaultBuiltInToFalse_when_usingBuilderDefaults() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("test-key")
                .content(Map.of("de", "test content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.isBuiltIn()).isFalse();
    }

    @Test
    void should_defaultSortOrderToZero_when_usingBuilderDefaults() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("test-key")
                .content(Map.of("de", "test content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.getSortOrder()).isZero();
    }

    @Test
    void should_defaultVersionToOne_when_usingBuilderDefaults() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("test-key")
                .content(Map.of("de", "test content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.getVersion()).isEqualTo(1);
    }

    @Test
    void should_storeLocalizedNames_when_nameMapPopulated() {
        Map<String, String> nameMap = new HashMap<>(Map.of("de", "Anfänger", "en", "Beginner"));

        OnboardingProfile profile = OnboardingProfile.builder()
                .key("beginner")
                .content(Map.of("de", "content"))
                .type(OnboardingProfileType.PROFILE)
                .name(nameMap)
                .build();

        assertThat(profile.getName())
                .containsEntry("de", "Anfänger")
                .containsEntry("en", "Beginner");
    }

    @Test
    void should_storeLocalizedDescriptions_when_descriptionMapPopulated() {
        Map<String, String> descMap = new HashMap<>(Map.of("de", "Für Einsteiger", "en", "For beginners"));

        OnboardingProfile profile = OnboardingProfile.builder()
                .key("beginner")
                .content(Map.of("de", "content"))
                .type(OnboardingProfileType.PROFILE)
                .description(descMap)
                .build();

        assertThat(profile.getDescription())
                .containsEntry("de", "Für Einsteiger")
                .containsEntry("en", "For beginners");
    }

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant now = Instant.now();

        OnboardingProfile profile = new OnboardingProfile(
                "test-id", "test-key",
                new HashMap<>(Map.of("en", "Test")),
                new HashMap<>(Map.of("en", "Desc")),
                Map.of("de", "content"), OnboardingProfileType.BASE,
                true, 5, 2,
                now, now
        );

        assertThat(profile.getId()).isEqualTo("test-id");
        assertThat(profile.getKey()).isEqualTo("test-key");
        assertThat(profile.getName()).containsEntry("en", "Test");
        assertThat(profile.getDescription()).containsEntry("en", "Desc");
        assertThat(profile.getContent()).isEqualTo(Map.of("de", "content"));
        assertThat(profile.getType()).isEqualTo(OnboardingProfileType.BASE);
        assertThat(profile.isBuiltIn()).isTrue();
        assertThat(profile.getSortOrder()).isEqualTo(5);
        assertThat(profile.getVersion()).isEqualTo(2);
        assertThat(profile.getCreatedAt()).isEqualTo(now);
        assertThat(profile.getUpdatedAt()).isEqualTo(now);
    }

    @Test
    void should_allowSettingAuditTimestamps_when_usingSetter() {
        OnboardingProfile profile = new OnboardingProfile();
        Instant created = Instant.parse("2024-01-01T00:00:00Z");
        Instant updated = Instant.parse("2024-06-01T12:00:00Z");

        profile.setCreatedAt(created);
        profile.setUpdatedAt(updated);

        assertThat(profile.getCreatedAt()).isEqualTo(created);
        assertThat(profile.getUpdatedAt()).isEqualTo(updated);
    }

    @Test
    void should_setTypeToBase_when_typeIsBase() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("base-key")
                .content(Map.of("de", "content"))
                .type(OnboardingProfileType.BASE)
                .build();

        assertThat(profile.getType()).isEqualTo(OnboardingProfileType.BASE);
    }

    @Test
    void should_setTypeToProfile_when_typeIsProfile() {
        OnboardingProfile profile = OnboardingProfile.builder()
                .key("profile-key")
                .content(Map.of("de", "content"))
                .type(OnboardingProfileType.PROFILE)
                .build();

        assertThat(profile.getType()).isEqualTo(OnboardingProfileType.PROFILE);
    }

    private void invokeEnsureId(OnboardingProfile profile) throws Exception {
        Method ensureId = OnboardingProfile.class.getDeclaredMethod("ensureId");
        ensureId.setAccessible(true);
        ensureId.invoke(profile);
    }
}
