package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingStatusResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of("profile-1", "profile-2"), true, "/etc/onboarding.yaml");

        assertThat(response.enabled()).isTrue();
        assertThat(response.format()).isEqualTo("yaml");
        assertThat(response.profileIds()).containsExactly("profile-1", "profile-2");
        assertThat(response.fileExists()).isTrue();
        assertThat(response.filePath()).isEqualTo("/etc/onboarding.yaml");
    }

    @Test
    void should_reflectFalseFlags_when_constructedWithFalseValues() {
        var response = new OnboardingStatusResponse(false, "json", List.of("p1"), false, "/tmp/onboarding.json");

        assertThat(response.enabled()).isFalse();
        assertThat(response.format()).isEqualTo("json");
        assertThat(response.profileIds()).containsExactly("p1");
        assertThat(response.fileExists()).isFalse();
        assertThat(response.filePath()).isEqualTo("/tmp/onboarding.json");
    }

    @Test
    void should_allowMixedFlags_when_enabledButFileNotExists() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of(), false, null);

        assertThat(response.enabled()).isTrue();
        assertThat(response.fileExists()).isFalse();
    }

    @Test
    void should_allowMixedFlags_when_notEnabledButFileExists() {
        var response = new OnboardingStatusResponse(false, "yaml", List.of(), true, "/etc/onboarding.yaml");

        assertThat(response.enabled()).isFalse();
        assertThat(response.fileExists()).isTrue();
    }

    // --- Null and empty list cases for profileIds ---

    @Test
    void should_returnEmptyList_when_profileIdsIsEmpty() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/etc/onboarding.yaml");

        assertThat(response.profileIds()).isEmpty();
    }

    @Test
    void should_acceptNullProfileIds_when_constructed() {
        var response = new OnboardingStatusResponse(true, "yaml", null, true, "/etc/onboarding.yaml");

        assertThat(response.profileIds()).isNull();
    }

    @Test
    void should_returnMultipleProfileIds_when_constructedWithMultiple() {
        var ids = List.of("id-1", "id-2", "id-3");
        var response = new OnboardingStatusResponse(true, "yaml", ids, true, "/path");

        assertThat(response.profileIds()).hasSize(3).containsExactly("id-1", "id-2", "id-3");
    }

    // --- Null string fields ---

    @Test
    void should_acceptNullFormat_when_constructed() {
        var response = new OnboardingStatusResponse(true, null, List.of(), true, "/etc/onboarding.yaml");

        assertThat(response.format()).isNull();
    }

    @Test
    void should_acceptNullFilePath_when_constructed() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of(), false, null);

        assertThat(response.filePath()).isNull();
    }

    @Test
    void should_acceptAllNullableFieldsNull_when_constructed() {
        var response = new OnboardingStatusResponse(false, null, null, false, null);

        assertThat(response.enabled()).isFalse();
        assertThat(response.format()).isNull();
        assertThat(response.profileIds()).isNull();
        assertThat(response.fileExists()).isFalse();
        assertThat(response.filePath()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of("p1"), true, "/path");
        var b = new OnboardingStatusResponse(true, "yaml", List.of("p1"), true, "/path");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothHaveEmptyProfileIds() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");
        var b = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_allNullableFieldsNull() {
        var a = new OnboardingStatusResponse(false, null, null, false, null);
        var b = new OnboardingStatusResponse(false, null, null, false, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentEnabled() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");
        var b = new OnboardingStatusResponse(false, "yaml", List.of(), true, "/path");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentFormat() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");
        var b = new OnboardingStatusResponse(true, "json", List.of(), true, "/path");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentProfileIds() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of("p1"), true, "/path");
        var b = new OnboardingStatusResponse(true, "yaml", List.of("p2"), true, "/path");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentFileExists() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");
        var b = new OnboardingStatusResponse(true, "yaml", List.of(), false, "/path");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentFilePath() {
        var a = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path/a");
        var b = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path/b");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_profileIdsNullVsEmpty() {
        var a = new OnboardingStatusResponse(true, "yaml", null, true, "/path");
        var b = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of(), true, "/path");

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new OnboardingStatusResponse(true, "yaml", List.of("profile-1"), true, "/etc/onboarding.yaml");
        var str = response.toString();

        assertThat(str).contains("true");
        assertThat(str).contains("yaml");
        assertThat(str).contains("profile-1");
        assertThat(str).contains("/etc/onboarding.yaml");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new OnboardingStatusResponse(false, null, null, false, null);
        var str = response.toString();

        assertThat(str).contains("false");
        assertThat(str).contains("null");
    }
}
