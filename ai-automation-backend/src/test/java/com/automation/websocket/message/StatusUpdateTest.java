package com.automation.websocket.message;

import com.automation.entity.RunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class StatusUpdateTest {

    // --- Constructor & accessors ---

    @Test
    void should_setAllFields_when_constructed() {
        var update = new StatusUpdate("run-42", RunStatus.RUNNING, "In progress");

        assertThat(update.runId()).isEqualTo("run-42");
        assertThat(update.status()).isEqualTo(RunStatus.RUNNING);
        assertThat(update.message()).isEqualTo("In progress");
    }

    @Test
    void should_acceptNullRunId_when_constructed() {
        var update = new StatusUpdate(null, RunStatus.COMPLETED, "Done");

        assertThat(update.runId()).isNull();
        assertThat(update.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(update.message()).isEqualTo("Done");
    }

    @Test
    void should_acceptNullStatus_when_constructed() {
        var update = new StatusUpdate("run-1", null, "msg");

        assertThat(update.runId()).isEqualTo("run-1");
        assertThat(update.status()).isNull();
        assertThat(update.message()).isEqualTo("msg");
    }

    @Test
    void should_acceptNullMessage_when_constructed() {
        var update = new StatusUpdate("run-1", RunStatus.FAILED, null);

        assertThat(update.runId()).isEqualTo("run-1");
        assertThat(update.status()).isEqualTo(RunStatus.FAILED);
        assertThat(update.message()).isNull();
    }

    @Test
    void should_acceptAllNulls_when_constructed() {
        var update = new StatusUpdate(null, null, null);

        assertThat(update.runId()).isNull();
        assertThat(update.status()).isNull();
        assertThat(update.message()).isNull();
    }

    // --- Record equality ---

    @Test
    void should_beEqual_when_sameFieldValues() {
        var a = new StatusUpdate("run-1", RunStatus.RUNNING, "msg");
        var b = new StatusUpdate("run-1", RunStatus.RUNNING, "msg");

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    @Test
    void should_beSymmetric_when_equal() {
        var a = new StatusUpdate("run-1", RunStatus.PAUSED, "paused");
        var b = new StatusUpdate("run-1", RunStatus.PAUSED, "paused");

        assertThat(a).isEqualTo(b);
        assertThat(b).isEqualTo(a);
    }

    @Test
    void should_notBeEqual_when_differentRunId() {
        var a = new StatusUpdate("run-1", RunStatus.RUNNING, "msg");
        var b = new StatusUpdate("run-2", RunStatus.RUNNING, "msg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentStatus() {
        var a = new StatusUpdate("run-1", RunStatus.RUNNING, "msg");
        var b = new StatusUpdate("run-1", RunStatus.COMPLETED, "msg");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_notBeEqual_when_differentMessage() {
        var a = new StatusUpdate("run-1", RunStatus.RUNNING, "msg-a");
        var b = new StatusUpdate("run-1", RunStatus.RUNNING, "msg-b");

        assertThat(a).isNotEqualTo(b);
    }

    @Test
    void should_beEqual_when_bothNullFields() {
        var a = new StatusUpdate(null, null, null);
        var b = new StatusUpdate(null, null, null);

        assertThat(a).isEqualTo(b);
        assertThat(a.hashCode()).isEqualTo(b.hashCode());
    }

    // --- toString ---

    @Test
    void should_containFieldValues_when_toStringCalled() {
        var update = new StatusUpdate("run-42", RunStatus.CANCELLED, "User cancelled");
        var str = update.toString();

        assertThat(str).contains("run-42");
        assertThat(str).contains("CANCELLED");
        assertThat(str).contains("User cancelled");
    }
}
