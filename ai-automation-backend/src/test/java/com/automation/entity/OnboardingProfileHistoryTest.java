package com.automation.entity;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileHistoryTest {

    // --- NoArgsConstructor defaults ---

    @Test
    void should_defaultIdToNull_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getId()).isNull();
    }

    @Test
    void should_defaultProfileIdToNull_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getProfileId()).isNull();
    }

    @Test
    void should_defaultVersionToZero_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getVersion()).isZero();
    }

    @Test
    void should_defaultContentToNull_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getContent()).isNull();
    }

    @Test
    void should_defaultChangedByToNull_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getChangedBy()).isNull();
    }

    @Test
    void should_defaultChangedAtToNull_when_usingNoArgsConstructor() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        assertThat(history.getChangedAt()).isNull();
    }

    // --- Setter / field access ---

    @Test
    void should_storeId_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        history.setId("hist-123");

        assertThat(history.getId()).isEqualTo("hist-123");
    }

    @Test
    void should_storeProfileId_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        history.setProfileId("profile-abc");

        assertThat(history.getProfileId()).isEqualTo("profile-abc");
    }

    @Test
    void should_storeVersion_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        history.setVersion(3);

        assertThat(history.getVersion()).isEqualTo(3);
    }

    @Test
    void should_storeContent_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        history.setContent("{\"name\": \"Test Profile\"}");

        assertThat(history.getContent()).isEqualTo("{\"name\": \"Test Profile\"}");
    }

    @Test
    void should_storeChangedBy_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();

        history.setChangedBy("admin-user");

        assertThat(history.getChangedBy()).isEqualTo("admin-user");
    }

    @Test
    void should_storeChangedAt_when_setViaSetter() {
        OnboardingProfileHistory history = new OnboardingProfileHistory();
        Instant ts = Instant.parse("2024-06-15T10:00:00Z");

        history.setChangedAt(ts);

        assertThat(history.getChangedAt()).isEqualTo(ts);
    }

    // --- Builder ---

    @Test
    void should_setAllFields_when_usingBuilder() {
        Instant changedAt = Instant.parse("2024-06-15T12:00:00Z");

        OnboardingProfileHistory history = OnboardingProfileHistory.builder()
                .id("hist-1")
                .profileId("profile-1")
                .version(2)
                .content("{\"skills\": [\"java\"]}")
                .changedBy("system")
                .changedAt(changedAt)
                .build();

        assertThat(history.getId()).isEqualTo("hist-1");
        assertThat(history.getProfileId()).isEqualTo("profile-1");
        assertThat(history.getVersion()).isEqualTo(2);
        assertThat(history.getContent()).isEqualTo("{\"skills\": [\"java\"]}");
        assertThat(history.getChangedBy()).isEqualTo("system");
        assertThat(history.getChangedAt()).isEqualTo(changedAt);
    }

    @Test
    void should_defaultFieldsToNull_when_usingBuilderWithNoValues() {
        OnboardingProfileHistory history = OnboardingProfileHistory.builder().build();

        assertThat(history.getId()).isNull();
        assertThat(history.getProfileId()).isNull();
        assertThat(history.getVersion()).isZero();
        assertThat(history.getContent()).isNull();
        assertThat(history.getChangedBy()).isNull();
        assertThat(history.getChangedAt()).isNull();
    }

    // --- AllArgsConstructor ---

    @Test
    void should_setAllFields_when_usingAllArgsConstructor() {
        Instant changedAt = Instant.parse("2024-06-15T14:00:00Z");

        OnboardingProfileHistory history = new OnboardingProfileHistory(
                "hist-all", "profile-all", 5,
                "{\"full\": true}", "migration-script", changedAt
        );

        assertThat(history.getId()).isEqualTo("hist-all");
        assertThat(history.getProfileId()).isEqualTo("profile-all");
        assertThat(history.getVersion()).isEqualTo(5);
        assertThat(history.getContent()).isEqualTo("{\"full\": true}");
        assertThat(history.getChangedBy()).isEqualTo("migration-script");
        assertThat(history.getChangedAt()).isEqualTo(changedAt);
    }

    // --- @PrePersist ensureId ---

    @Test
    void should_generateUuid_when_idIsNullOnPrePersist() throws Exception {
        OnboardingProfileHistory history = new OnboardingProfileHistory();
        assertThat(history.getId()).isNull();

        invokeEnsureId(history);

        assertThat(history.getId()).isNotNull().isNotBlank();
        assertThat(history.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_generateUuid_when_idIsBlankOnPrePersist() throws Exception {
        OnboardingProfileHistory history = new OnboardingProfileHistory();
        history.setId("   ");

        invokeEnsureId(history);

        assertThat(history.getId()).isNotBlank();
        assertThat(history.getId()).doesNotContain("   ");
        assertThat(history.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_generateUuid_when_idIsEmptyStringOnPrePersist() throws Exception {
        OnboardingProfileHistory history = new OnboardingProfileHistory();
        history.setId("");

        invokeEnsureId(history);

        assertThat(history.getId()).isNotBlank();
        assertThat(history.getId()).matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
    }

    @Test
    void should_keepExistingId_when_idIsAlreadySetOnPrePersist() throws Exception {
        OnboardingProfileHistory history = new OnboardingProfileHistory();
        history.setId("custom-id-123");

        invokeEnsureId(history);

        assertThat(history.getId()).isEqualTo("custom-id-123");
    }

    @Test
    void should_generateUniqueIds_when_calledOnDifferentInstances() throws Exception {
        OnboardingProfileHistory h1 = new OnboardingProfileHistory();
        OnboardingProfileHistory h2 = new OnboardingProfileHistory();

        invokeEnsureId(h1);
        invokeEnsureId(h2);

        assertThat(h1.getId()).isNotEqualTo(h2.getId());
    }

    /**
     * Invokes the private @PrePersist method via reflection.
     */
    private void invokeEnsureId(OnboardingProfileHistory history) throws Exception {
        Method ensureId = OnboardingProfileHistory.class.getDeclaredMethod("ensureId");
        ensureId.setAccessible(true);
        ensureId.invoke(history);
    }
}
