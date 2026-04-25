package com.automation.service.document;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentExtractionExceptionTest {

    @Test
    void constructor_withMessage_setsMessageAndNoCause() {
        DocumentExtractionException ex = new DocumentExtractionException("boom");

        assertThat(ex.getMessage()).isEqualTo("boom");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void constructor_withMessageAndCause_propagatesBoth() {
        Throwable cause = new IllegalStateException("root");

        DocumentExtractionException ex = new DocumentExtractionException("wrapped", cause);

        assertThat(ex.getMessage()).isEqualTo("wrapped");
        assertThat(ex.getCause()).isSameAs(cause);
    }

    @Test
    void constructor_withNullMessage_allowsNullMessage() {
        DocumentExtractionException ex = new DocumentExtractionException(null);

        assertThat(ex.getMessage()).isNull();
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void constructor_withNullCause_allowsNullCause() {
        DocumentExtractionException ex = new DocumentExtractionException("msg", null);

        assertThat(ex.getMessage()).isEqualTo("msg");
        assertThat(ex.getCause()).isNull();
    }

    @Test
    void exception_isRuntimeException() {
        DocumentExtractionException ex = new DocumentExtractionException("x");

        assertThat(ex).isInstanceOf(RuntimeException.class);
    }
}
