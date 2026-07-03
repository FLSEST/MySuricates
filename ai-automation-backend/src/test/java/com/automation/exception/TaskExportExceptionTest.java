package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TaskExportExceptionTest {

    @Test
    void should_storeCodeAndMessage_when_constructedWithCodeAndMessage() {
        var ex = new TaskExportException(ErrorCode.NO_TASK_TARGET, "no target configured");

        assertThat(ex.getCode()).isEqualTo(ErrorCode.NO_TASK_TARGET);
        assertThat(ex.getMessage()).isEqualTo("no target configured");
        assertThat(ex.getCause()).isNull();
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_storeGivenErrorCode_when_constructedWithDifferentCodes() {
        var notConnected = new TaskExportException(ErrorCode.PROVIDER_NOT_CONNECTED, "provider offline");
        var notCapable = new TaskExportException(ErrorCode.PROVIDER_NOT_TASK_CAPABLE, "provider cannot receive tasks");

        assertThat(notConnected.getCode()).isEqualTo(ErrorCode.PROVIDER_NOT_CONNECTED);
        assertThat(notConnected.getMessage()).isEqualTo("provider offline");
        assertThat(notCapable.getCode()).isEqualTo(ErrorCode.PROVIDER_NOT_TASK_CAPABLE);
        assertThat(notCapable.getMessage()).isEqualTo("provider cannot receive tasks");
    }

    @Test
    void should_tolerateNullMessage_when_constructedWithNullMessage() {
        var ex = new TaskExportException(ErrorCode.NO_TASK_TARGET, null);

        assertThat(ex.getMessage()).isNull();
        assertThat(ex.getCode()).isEqualTo(ErrorCode.NO_TASK_TARGET);
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void should_tolerateNullCode_when_constructedWithNullCode() {
        var ex = new TaskExportException(null, "message without code");

        assertThat(ex.getCode()).isNull();
        assertThat(ex.getMessage()).isEqualTo("message without code");
    }
}
