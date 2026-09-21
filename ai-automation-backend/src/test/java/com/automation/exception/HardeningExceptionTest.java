package com.automation.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class HardeningExceptionTest {

    @Test
    void carriesCodeAndMessage() {
        var ex = new HardeningException(ErrorCode.WORKFLOW_SEALED, "Workflow 'x' is sealed");

        assertThat(ex).isInstanceOf(RuntimeException.class);
        assertThat(ex.getCode()).isEqualTo(ErrorCode.WORKFLOW_SEALED);
        assertThat(ex.getMessage()).isEqualTo("Workflow 'x' is sealed");
        assertThat(ex.getCause()).isNull();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
            "WORKFLOW_SEALED, CONFLICT",
            "WORKFLOW_TAMPERED, CONFLICT",
            "UNATTENDED_REQUIRES_SEAL, FORBIDDEN",
            "WEBHOOK_SIGNATURE_INVALID, UNAUTHORIZED"
    })
    void handlerAnswersWithTheStatusOfTheCarriedCodeInsteadOf500(ErrorCode code, HttpStatus status) {
        ResponseEntity<ErrorResponse> response =
                new GlobalExceptionHandler().handleHardening(new HardeningException(code, "refused: " + code));

        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo(code.name());
        assertThat(response.getBody().getError()).isEqualTo("refused: " + code);
    }

    @Test
    void handlerFallsBackToTheDefaultMessageWhenNoneIsGiven() {
        ResponseEntity<ErrorResponse> response = new GlobalExceptionHandler()
                .handleHardening(new HardeningException(ErrorCode.WORKFLOW_TAMPERED, null));

        assertThat(response.getBody().getError()).isEqualTo(ErrorCode.WORKFLOW_TAMPERED.getDefaultMessage());
    }
}
