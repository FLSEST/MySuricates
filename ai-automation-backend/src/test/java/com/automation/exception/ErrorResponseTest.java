package com.automation.exception;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorResponseTest {

    @Test
    void should_setCodeAndCustomMessage_when_createdWithOfAndMessage() {
        var response = ErrorResponse.of(ErrorCode.VALIDATION_FAILED, "Name must not be blank");

        assertThat(response.getCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getError()).isEqualTo("Name must not be blank");
        assertThat(response.getTimestamp()).isNotBlank();
    }

    @Test
    void should_useDefaultMessage_when_createdWithOfAndNullMessage() {
        var response = ErrorResponse.of(ErrorCode.RESOURCE_NOT_FOUND, null);

        assertThat(response.getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getError()).isEqualTo("Resource not found");
        assertThat(response.getTimestamp()).isNotBlank();
    }

    @Test
    void should_useDefaultMessage_when_createdWithSingleArgOf() {
        var response = ErrorResponse.of(ErrorCode.CONTAINER_ERROR);

        assertThat(response.getCode()).isEqualTo("CONTAINER_ERROR");
        assertThat(response.getError()).isEqualTo("Container error");
        assertThat(response.getTimestamp()).isNotBlank();
    }

    @Test
    void should_setAllFields_when_createdViaBuilder() {
        var fieldErrors = Map.of("email", "must be valid");

        var response = ErrorResponse.builder()
                .code("VALIDATION_FAILED")
                .error("Validation failed")
                .timestamp("2026-01-01T00:00:00Z")
                .fieldErrors(fieldErrors)
                .details("Additional detail")
                .build();

        assertThat(response.getCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getError()).isEqualTo("Validation failed");
        assertThat(response.getTimestamp()).isEqualTo("2026-01-01T00:00:00Z");
        assertThat(response.getFieldErrors()).containsEntry("email", "must be valid");
        assertThat(response.getDetails()).isEqualTo("Additional detail");
    }

    @Test
    void should_haveNullOptionalFields_when_createdViaOf() {
        var response = ErrorResponse.of(ErrorCode.INTERNAL_ERROR);

        assertThat(response.getFieldErrors()).isNull();
        assertThat(response.getDetails()).isNull();
    }

    @Test
    void should_preserveTimestampFormat_when_createdViaOf() {
        var response = ErrorResponse.of(ErrorCode.OPERATION_TIMEOUT, "Timed out after 30s");

        // Instant.toString() produces ISO-8601 format
        assertThat(response.getTimestamp()).matches("\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}.*Z");
    }

    @Test
    void should_leaveParamsOutOfTheJson_when_theMessageIsFreeText() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(ErrorResponse.of(ErrorCode.INVALID_ARGUMENT, "Invalid cron expression: x"));

        assertThat(json).doesNotContain("params");
    }

    @Test
    void should_writeParams_evenWhenEmpty_because_emptyStillMeansTranslatable() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();

        assertThat(mapper.writeValueAsString(ErrorResponse.of(ErrorCode.INTERNAL_ERROR, "Internal server error", Map.of())))
                .contains("\"params\":{}");
        assertThat(mapper.writeValueAsString(ErrorResponse.of(ErrorCode.WORKFLOW_SEALED, "m", Map.of("name", "Nightly"))))
                .contains("\"params\":{\"name\":\"Nightly\"}");
    }
}
