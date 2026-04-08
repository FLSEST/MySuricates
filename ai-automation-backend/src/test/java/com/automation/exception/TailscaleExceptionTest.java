package com.automation.exception;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TailscaleExceptionTest {

    @Test
    void should_storeMessage_when_constructedWithMessage() {
        var ex = new TailscaleException("Tailscale connection failed");

        assertThat(ex.getMessage()).isEqualTo("Tailscale connection failed");
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }

    @Test
    void should_storeMessageAndCause_when_constructedWithMessageAndCause() {
        var cause = new IllegalStateException("root cause");
        var ex = new TailscaleException("Tailscale connection failed", cause);

        assertThat(ex.getMessage()).isEqualTo("Tailscale connection failed");
        assertThat(ex.getCause()).isSameAs(cause);
        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
