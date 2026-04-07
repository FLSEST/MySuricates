package com.automation.dto.request;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class OnboardingConfigRequestTest {

    // --- Record accessors ---

    @Test
    void should_returnFormat_when_constructed() {
        var request = new OnboardingConfigRequest("json", List.of("p1", "p2"));

        assertThat(request.format()).isEqualTo("json");
    }

    @Test
    void should_returnProfileIds_when_constructed() {
        var request = new OnboardingConfigRequest("json", List.of("p1", "p2"));

        assertThat(request.profileIds()).containsExactly("p1", "p2");
    }

    // --- Null / empty edge cases ---

    @Test
    void should_returnNull_when_formatIsNull() {
        var request = new OnboardingConfigRequest(null, List.of("p1"));

        assertThat(request.format()).isNull();
    }

    @Test
    void should_returnNull_when_profileIdsIsNull() {
        var request = new OnboardingConfigRequest("json", null);

        assertThat(request.profileIds()).isNull();
    }

    @Test
    void should_returnEmptyList_when_profileIdsIsEmpty() {
        var request = new OnboardingConfigRequest("json", Collections.emptyList());

        assertThat(request.profileIds()).isEmpty();
    }

    @Test
    void should_allowBothNull() {
        var request = new OnboardingConfigRequest(null, null);

        assertThat(request.format()).isNull();
        assertThat(request.profileIds()).isNull();
    }

    // --- equals / hashCode ---

    @Test
    void should_beEqual_when_sameValues() {
        var a = new OnboardingConfigRequest("json", List.of("p1"));
        var b = new OnboardingConfigRequest("json", List.of("p1"));

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothNull() {
        var a = new OnboardingConfigRequest(null, null);
        var b = new OnboardingConfigRequest(null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentFormat() {
        var a = new OnboardingConfigRequest("json", List.of("p1"));
        var b = new OnboardingConfigRequest("xml", List.of("p1"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentProfileIds() {
        var a = new OnboardingConfigRequest("json", List.of("p1"));
        var b = new OnboardingConfigRequest("json", List.of("p2"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var request = new OnboardingConfigRequest("json", List.of("p1"));

        assertThat(request).isNotEqualTo(null);
    }

    @Test
    void should_notBeEqual_when_comparedToDifferentType() {
        var request = new OnboardingConfigRequest("json", List.of("p1"));

        assertThat(request).isNotEqualTo("not a request");
    }

    // --- toString ---

    @Test
    void should_containFormat_inToString() {
        var request = new OnboardingConfigRequest("json", List.of("p1"));

        assertThat(request.toString()).contains("format=json");
    }

    @Test
    void should_containProfileIds_inToString() {
        var request = new OnboardingConfigRequest("json", List.of("p1", "p2"));

        assertThat(request.toString()).contains("profileIds=");
    }
}
