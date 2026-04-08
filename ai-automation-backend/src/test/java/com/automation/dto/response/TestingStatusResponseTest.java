package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestingStatusResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");

        assertThat(response.containerRunning()).isTrue();
        assertThat(response.healthy()).isTrue();
        assertThat(response.testVersion()).isEqualTo("1.0.0");
        assertThat(response.testBuildTimestamp()).isEqualTo("2024-01-15T10:30:00Z");
    }

    @Test
    void should_reflectFalseFlags_when_constructedWithFalseValues() {
        var response = new TestingStatusResponse(false, false, "2.0.0", "2024-06-01T08:00:00Z");

        assertThat(response.containerRunning()).isFalse();
        assertThat(response.healthy()).isFalse();
        assertThat(response.testVersion()).isEqualTo("2.0.0");
        assertThat(response.testBuildTimestamp()).isEqualTo("2024-06-01T08:00:00Z");
    }

    @Test
    void should_allowMixedFlags_when_runningButNotHealthy() {
        var response = new TestingStatusResponse(true, false, "1.0.0", "2024-01-01T00:00:00Z");

        assertThat(response.containerRunning()).isTrue();
        assertThat(response.healthy()).isFalse();
    }

    @Test
    void should_allowMixedFlags_when_healthyButNotRunning() {
        var response = new TestingStatusResponse(false, true, "1.0.0", "2024-01-01T00:00:00Z");

        assertThat(response.containerRunning()).isFalse();
        assertThat(response.healthy()).isTrue();
    }

    @Test
    void should_acceptNullTestVersion_when_constructed() {
        var response = new TestingStatusResponse(true, true, null, "2024-01-15T10:30:00Z");

        assertThat(response.containerRunning()).isTrue();
        assertThat(response.healthy()).isTrue();
        assertThat(response.testVersion()).isNull();
        assertThat(response.testBuildTimestamp()).isEqualTo("2024-01-15T10:30:00Z");
    }

    @Test
    void should_acceptNullTestBuildTimestamp_when_constructed() {
        var response = new TestingStatusResponse(true, true, "1.0.0", null);

        assertThat(response.testVersion()).isEqualTo("1.0.0");
        assertThat(response.testBuildTimestamp()).isNull();
    }

    @Test
    void should_acceptBothNullStrings_when_constructed() {
        var response = new TestingStatusResponse(false, false, null, null);

        assertThat(response.containerRunning()).isFalse();
        assertThat(response.healthy()).isFalse();
        assertThat(response.testVersion()).isNull();
        assertThat(response.testBuildTimestamp()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var b = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentContainerRunning() {
        var a = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var b = new TestingStatusResponse(false, true, "1.0.0", "2024-01-15T10:30:00Z");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentHealthy() {
        var a = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var b = new TestingStatusResponse(true, false, "1.0.0", "2024-01-15T10:30:00Z");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTestVersion() {
        var a = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var b = new TestingStatusResponse(true, true, "2.0.0", "2024-01-15T10:30:00Z");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentTestBuildTimestamp() {
        var a = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var b = new TestingStatusResponse(true, true, "1.0.0", "2025-06-01T00:00:00Z");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothNullStrings() {
        var a = new TestingStatusResponse(false, false, null, null);
        var b = new TestingStatusResponse(false, false, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new TestingStatusResponse(true, true, "1.0.0", "2024-01-15T10:30:00Z");
        var str = response.toString();

        assertThat(str).contains("true");
        assertThat(str).contains("1.0.0");
        assertThat(str).contains("2024-01-15T10:30:00Z");
    }

    @Test
    void should_handleNullsInToString_when_fieldsAreNull() {
        var response = new TestingStatusResponse(false, false, null, null);
        var str = response.toString();

        assertThat(str).contains("false");
        assertThat(str).contains("null");
    }
}
