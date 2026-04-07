package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TodoCountResponseTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new TodoCountResponse(5, 3, 10, 2);

        assertThat(response.open()).isEqualTo(5);
        assertThat(response.inProgress()).isEqualTo(3);
        assertThat(response.done()).isEqualTo(10);
        assertThat(response.backlog()).isEqualTo(2);
    }

    @Test
    void should_returnZero_when_allCountsAreZero() {
        var response = new TodoCountResponse(0, 0, 0, 0);

        assertThat(response.open()).isZero();
        assertThat(response.inProgress()).isZero();
        assertThat(response.done()).isZero();
        assertThat(response.backlog()).isZero();
    }

    @Test
    void should_handleLargeValues_when_constructedWithHighCounts() {
        var response = new TodoCountResponse(100_000, 50_000, 999_999, 1);

        assertThat(response.open()).isEqualTo(100_000);
        assertThat(response.inProgress()).isEqualTo(50_000);
        assertThat(response.done()).isEqualTo(999_999);
        assertThat(response.backlog()).isEqualTo(1);
    }

    @Test
    void should_handleMixedCounts_when_onlyOneFieldIsNonZero() {
        var openOnly = new TodoCountResponse(7, 0, 0, 0);
        assertThat(openOnly.open()).isEqualTo(7);
        assertThat(openOnly.inProgress()).isZero();

        var inProgressOnly = new TodoCountResponse(0, 4, 0, 0);
        assertThat(inProgressOnly.inProgress()).isEqualTo(4);
        assertThat(inProgressOnly.done()).isZero();

        var doneOnly = new TodoCountResponse(0, 0, 12, 0);
        assertThat(doneOnly.done()).isEqualTo(12);
        assertThat(doneOnly.backlog()).isZero();

        var backlogOnly = new TodoCountResponse(0, 0, 0, 8);
        assertThat(backlogOnly.backlog()).isEqualTo(8);
        assertThat(backlogOnly.open()).isZero();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new TodoCountResponse(5, 3, 10, 2);
        var b = new TodoCountResponse(5, 3, 10, 2);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beEqual_when_bothAllZeros() {
        var a = new TodoCountResponse(0, 0, 0, 0);
        var b = new TodoCountResponse(0, 0, 0, 0);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentOpen() {
        var a = new TodoCountResponse(5, 3, 10, 2);
        var b = new TodoCountResponse(6, 3, 10, 2);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentInProgress() {
        var a = new TodoCountResponse(5, 3, 10, 2);
        var b = new TodoCountResponse(5, 4, 10, 2);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentDone() {
        var a = new TodoCountResponse(5, 3, 10, 2);
        var b = new TodoCountResponse(5, 3, 11, 2);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentBacklog() {
        var a = new TodoCountResponse(5, 3, 10, 2);
        var b = new TodoCountResponse(5, 3, 10, 3);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new TodoCountResponse(1, 2, 3, 4);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new TodoCountResponse(5, 3, 10, 2);
        var str = response.toString();

        assertThat(str).contains("5");
        assertThat(str).contains("3");
        assertThat(str).contains("10");
        assertThat(str).contains("2");
    }

    @Test
    void should_containZeros_when_toStringCalledOnEmptyResponse() {
        var response = new TodoCountResponse(0, 0, 0, 0);
        var str = response.toString();

        assertThat(str).contains("0");
    }
}
