package com.automation.dto.response;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class WorkflowVersionResponseTest {

    private static final Instant CREATED_AT = Instant.parse("2025-11-03T14:22:37.456Z");

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"blocks\":[]}", "USER", "Initial version", CREATED_AT);

        assertThat(response.id()).isEqualTo("v-1");
        assertThat(response.workflowId()).isEqualTo("wf-1");
        assertThat(response.versionNumber()).isEqualTo(3);
        assertThat(response.snapshot()).isEqualTo("{\"blocks\":[]}");
        assertThat(response.changeSource()).isEqualTo("USER");
        assertThat(response.changeMessage()).isEqualTo("Initial version");
        assertThat(response.createdAt()).isEqualTo(CREATED_AT);
    }

    @Test
    void should_acceptNullId_when_constructed() {
        var response = new WorkflowVersionResponse(null, "wf-1", 1, "{}", "USER", "msg", CREATED_AT);

        assertThat(response.id()).isNull();
        assertThat(response.workflowId()).isEqualTo("wf-1");
    }

    @Test
    void should_acceptNullWorkflowId_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", null, 1, "{}", "USER", "msg", CREATED_AT);

        assertThat(response.workflowId()).isNull();
        assertThat(response.id()).isEqualTo("v-1");
    }

    @Test
    void should_acceptNullSnapshot_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, null, "USER", "msg", CREATED_AT);

        assertThat(response.snapshot()).isNull();
    }

    @Test
    void should_acceptNullChangeSource_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", null, "msg", CREATED_AT);

        assertThat(response.changeSource()).isNull();
    }

    @Test
    void should_acceptNullChangeMessage_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", null, CREATED_AT);

        assertThat(response.changeMessage()).isNull();
    }

    @Test
    void should_acceptNullCreatedAt_when_constructed() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", null);

        assertThat(response.createdAt()).isNull();
    }

    @Test
    void should_acceptAllNullableFieldsNull_when_constructed() {
        var response = new WorkflowVersionResponse(null, null, 0, null, null, null, null);

        assertThat(response.id()).isNull();
        assertThat(response.workflowId()).isNull();
        assertThat(response.versionNumber()).isZero();
        assertThat(response.snapshot()).isNull();
        assertThat(response.changeSource()).isNull();
        assertThat(response.changeMessage()).isNull();
        assertThat(response.createdAt()).isNull();
    }

    // --- Boundary values for versionNumber ---

    @Test
    void should_acceptZeroVersionNumber_when_constructed() {
        var response = new WorkflowVersionResponse("v-0", "wf-1", 0, "{}", "SYSTEM", "init", CREATED_AT);

        assertThat(response.versionNumber()).isZero();
    }

    @Test
    void should_acceptNegativeVersionNumber_when_constructed() {
        var response = new WorkflowVersionResponse("v-neg", "wf-1", -1, "{}", "SYSTEM", "rollback", CREATED_AT);

        assertThat(response.versionNumber()).isEqualTo(-1);
    }

    @Test
    void should_acceptMaxIntVersionNumber_when_constructed() {
        var response = new WorkflowVersionResponse("v-max", "wf-1", Integer.MAX_VALUE, "{}", "SYSTEM", "edge", CREATED_AT);

        assertThat(response.versionNumber()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void should_acceptMinIntVersionNumber_when_constructed() {
        var response = new WorkflowVersionResponse("v-min", "wf-1", Integer.MIN_VALUE, "{}", "SYSTEM", "edge", CREATED_AT);

        assertThat(response.versionNumber()).isEqualTo(Integer.MIN_VALUE);
    }

    // --- Instant edge cases ---

    @Test
    void should_acceptEpochInstant_when_constructed() {
        var epoch = Instant.EPOCH;
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", epoch);

        assertThat(response.createdAt()).isEqualTo(epoch);
        assertThat(response.createdAt().getEpochSecond()).isZero();
    }

    @Test
    void should_preserveNanosecondPrecision_when_constructed() {
        var precise = Instant.parse("2025-06-15T10:30:00.123456789Z");
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", precise);

        assertThat(response.createdAt()).isEqualTo(precise);
        assertThat(response.createdAt().getNano()).isEqualTo(123456789);
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"blocks\":[]}", "USER", "Initial", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"blocks\":[]}", "USER", "Initial", CREATED_AT);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_differentId() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-2", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentWorkflowId() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-2", 3, "{}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentVersionNumber() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 4, "{}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentSnapshot() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"a\":1}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"b\":2}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChangeSource() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "SYSTEM", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentChangeMessage() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg1", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg2", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentCreatedAt() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 3, "{}", "USER", "msg", Instant.parse("2026-01-01T00:00:00Z"));

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothHaveAllNullableFieldsNull() {
        var a = new WorkflowVersionResponse(null, null, 0, null, null, null, null);
        var b = new WorkflowVersionResponse(null, null, 0, null, null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullChangeSource() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", null, "msg", CREATED_AT);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_nullVsNonNullCreatedAt() {
        var a = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", null);
        var b = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", CREATED_AT);

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_comparedToNull() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 1, "{}", "USER", "msg", CREATED_AT);

        assertThat(response).isNotEqualTo(null);
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var response = new WorkflowVersionResponse("v-1", "wf-1", 3, "{\"blocks\":[]}", "USER", "Initial version", CREATED_AT);
        var str = response.toString();

        assertThat(str).contains("v-1");
        assertThat(str).contains("wf-1");
        assertThat(str).contains("3");
        assertThat(str).contains("{\"blocks\":[]}");
        assertThat(str).contains("USER");
        assertThat(str).contains("Initial version");
        assertThat(str).contains("2025-11-03T14:22:37.456Z");
    }

    @Test
    void should_handleNullsInToString_when_nullableFieldsAreNull() {
        var response = new WorkflowVersionResponse(null, null, 0, null, null, null, null);
        var str = response.toString();

        assertThat(str).contains("null");
        assertThat(str).contains("0");
    }
}
