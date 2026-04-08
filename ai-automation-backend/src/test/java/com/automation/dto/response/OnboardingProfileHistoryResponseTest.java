package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingProfileHistoryResponseTest {

    private static final Instant CHANGED_AT = Instant.parse("2025-11-03T14:22:37.456Z");

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);

        assertThat(response.id()).isEqualTo("hist-1");
        assertThat(response.version()).isEqualTo(3);
        assertThat(response.content()).isEqualTo("Profile updated");
        assertThat(response.changedBy()).isEqualTo("admin");
        assertThat(response.changedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void should_acceptZeroVersion_when_constructed() {
        var response = new OnboardingProfileHistoryResponse("hist-0", 0, "Initial", "system", CHANGED_AT);

        assertThat(response.version()).isZero();
    }

    @Test
    void should_acceptNullChangedBy_when_constructed() {
        var response = new OnboardingProfileHistoryResponse("hist-2", 1, "Auto change", null, CHANGED_AT);

        assertThat(response.id()).isEqualTo("hist-2");
        assertThat(response.version()).isEqualTo(1);
        assertThat(response.content()).isEqualTo("Auto change");
        assertThat(response.changedBy()).isNull();
        assertThat(response.changedAt()).isEqualTo(CHANGED_AT);
    }

    @Test
    void should_acceptNullChangedAt_when_constructed() {
        var response = new OnboardingProfileHistoryResponse("hist-3", 2, "Legacy entry", "user1", null);

        assertThat(response.id()).isEqualTo("hist-3");
        assertThat(response.version()).isEqualTo(2);
        assertThat(response.content()).isEqualTo("Legacy entry");
        assertThat(response.changedBy()).isEqualTo("user1");
        assertThat(response.changedAt()).isNull();
    }

    @Test
    void should_acceptBothNullOptionalFields_when_constructed() {
        var response = new OnboardingProfileHistoryResponse("hist-4", 1, "Unknown origin", null, null);

        assertThat(response.changedBy()).isNull();
        assertThat(response.changedAt()).isNull();
        assertThat(response.id()).isEqualTo("hist-4");
        assertThat(response.content()).isEqualTo("Unknown origin");
    }

    @Test
    void should_acceptNullIdAndContent_when_constructed() {
        var response = new OnboardingProfileHistoryResponse(null, 0, null, null, null);

        assertThat(response.id()).isNull();
        assertThat(response.version()).isZero();
        assertThat(response.content()).isNull();
        assertThat(response.changedBy()).isNull();
        assertThat(response.changedAt()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-2", 3, "Profile updated", "admin", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentVersion() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 4, "Profile updated", "admin", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentContent() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 3, "Name changed", "admin", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChangedBy() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "user2", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChangedAt() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothHaveNullOptionalFields() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 1, "content", null, null);
        var b = new OnboardingProfileHistoryResponse("hist-1", 1, "content", null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullChangedBy() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 1, "content", null, CHANGED_AT);
        var b = new OnboardingProfileHistoryResponse("hist-1", 1, "content", "admin", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullChangedAt() {
        var a = new OnboardingProfileHistoryResponse("hist-1", 1, "content", "admin", null);
        var b = new OnboardingProfileHistoryResponse("hist-1", 1, "content", "admin", CHANGED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new OnboardingProfileHistoryResponse("hist-1", 3, "Profile updated", "admin", CHANGED_AT);
        var str = response.toString();

        assertThat(str).contains("hist-1");
        assertThat(str).contains("3");
        assertThat(str).contains("Profile updated");
        assertThat(str).contains("admin");
        assertThat(str).contains("2025-11-03T14:22:37.456Z");
    }

    @Test
    void should_handleNullsInToString_when_optionalFieldsAreNull() {
        var response = new OnboardingProfileHistoryResponse("hist-5", 1, "entry", null, null);
        var str = response.toString();

        assertThat(str).contains("hist-5");
        assertThat(str).contains("null");
    }
}
