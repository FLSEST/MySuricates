package com.automation.exception;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ErrorCodeTest {

    @Test
    void should_containAllExpectedValues_when_enumValuesQueried() {
        assertThat(ErrorCode.values()).hasSize(26)
                .containsExactly(
                        ErrorCode.RESOURCE_NOT_FOUND,
                        ErrorCode.MODULE_DISABLED,
                        ErrorCode.MODULES_NOT_EDITABLE,
                        ErrorCode.VALIDATION_FAILED,
                        ErrorCode.INVALID_ARGUMENT,
                        ErrorCode.WORKFLOW_EXECUTION_FAILED,
                        ErrorCode.CONTAINER_ERROR,
                        ErrorCode.STAGING_ERROR,
                        ErrorCode.STATE_CONFLICT,
                        ErrorCode.WORKFLOW_SEALED,
                        ErrorCode.WORKFLOW_TAMPERED,
                        ErrorCode.SKILL_PINNED_BY_SEAL,
                        ErrorCode.SEAL_IMMUTABLE,
                        ErrorCode.UNATTENDED_REQUIRES_SEAL,
                        ErrorCode.WEBHOOK_SIGNATURE_INVALID,
                        ErrorCode.NO_TASK_TARGET,
                        ErrorCode.PROVIDER_NOT_CONNECTED,
                        ErrorCode.PROVIDER_NOT_TASK_CAPABLE,
                        ErrorCode.SERVICE_UNAVAILABLE,
                        ErrorCode.TOKEN_BUDGET_EXCEEDED,
                        ErrorCode.RATE_LIMITED,
                        ErrorCode.PAYLOAD_TOO_LARGE,
                        ErrorCode.OPERATION_TIMEOUT,
                        ErrorCode.METHOD_NOT_ALLOWED,
                        ErrorCode.RUN_DELETION_DISABLED,
                        ErrorCode.INTERNAL_ERROR
                );
    }

    @ParameterizedTest
    @MethodSource("errorCodeMappings")
    void should_returnCorrectHttpStatusAndMessage_when_enumValueAccessed(
            ErrorCode errorCode, HttpStatus expectedStatus, String expectedMessage) {
        assertThat(errorCode.getHttpStatus()).isEqualTo(expectedStatus);
        assertThat(errorCode.getDefaultMessage()).isEqualTo(expectedMessage);
    }

    @Test
    void should_resolveEnumConstant_when_validNameProvided() {
        assertThat(ErrorCode.valueOf("RESOURCE_NOT_FOUND")).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
        assertThat(ErrorCode.valueOf("INTERNAL_ERROR")).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void should_throwIllegalArgumentException_when_invalidNameProvided() {
        assertThatThrownBy(() -> ErrorCode.valueOf("NON_EXISTENT"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void should_mapToClientErrorStatus_when_clientErrorCodeUsed() {
        assertThat(ErrorCode.RESOURCE_NOT_FOUND.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.MODULE_DISABLED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.MODULES_NOT_EDITABLE.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.VALIDATION_FAILED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.INVALID_ARGUMENT.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.STATE_CONFLICT.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.WORKFLOW_SEALED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.WORKFLOW_TAMPERED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.UNATTENDED_REQUIRES_SEAL.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.WEBHOOK_SIGNATURE_INVALID.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.NO_TASK_TARGET.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.PROVIDER_NOT_CONNECTED.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.PROVIDER_NOT_TASK_CAPABLE.getHttpStatus().is4xxClientError()).isTrue();
        assertThat(ErrorCode.TOKEN_BUDGET_EXCEEDED.getHttpStatus().is4xxClientError()).isTrue();
    }

    @Test
    void should_mapToServerErrorStatus_when_serverErrorCodeUsed() {
        assertThat(ErrorCode.WORKFLOW_EXECUTION_FAILED.getHttpStatus().is5xxServerError()).isTrue();
        assertThat(ErrorCode.CONTAINER_ERROR.getHttpStatus().is5xxServerError()).isTrue();
        assertThat(ErrorCode.STAGING_ERROR.getHttpStatus().is5xxServerError()).isTrue();
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.getHttpStatus().is5xxServerError()).isTrue();
        assertThat(ErrorCode.OPERATION_TIMEOUT.getHttpStatus().is5xxServerError()).isTrue();
        assertThat(ErrorCode.INTERNAL_ERROR.getHttpStatus().is5xxServerError()).isTrue();
    }

    private static Stream<Arguments> errorCodeMappings() {
        return Stream.of(
                Arguments.of(ErrorCode.RESOURCE_NOT_FOUND, HttpStatus.NOT_FOUND, "Resource not found"),
                Arguments.of(ErrorCode.MODULE_DISABLED, HttpStatus.NOT_FOUND, "Module disabled"),
                Arguments.of(ErrorCode.MODULES_NOT_EDITABLE, HttpStatus.FORBIDDEN, "Modules cannot be switched at runtime"),
                Arguments.of(ErrorCode.VALIDATION_FAILED, HttpStatus.BAD_REQUEST, "Validation failed"),
                Arguments.of(ErrorCode.INVALID_ARGUMENT, HttpStatus.BAD_REQUEST, "Invalid argument"),
                Arguments.of(ErrorCode.WORKFLOW_EXECUTION_FAILED, HttpStatus.INTERNAL_SERVER_ERROR, "Workflow execution failed"),
                Arguments.of(ErrorCode.CONTAINER_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Container error"),
                Arguments.of(ErrorCode.STAGING_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Staging error"),
                Arguments.of(ErrorCode.STATE_CONFLICT, HttpStatus.CONFLICT, "State conflict"),
                Arguments.of(ErrorCode.WORKFLOW_SEALED, HttpStatus.CONFLICT, "Workflow is sealed"),
                Arguments.of(ErrorCode.WORKFLOW_TAMPERED, HttpStatus.CONFLICT, "Sealed workflow definition was modified"),
                Arguments.of(ErrorCode.SKILL_PINNED_BY_SEAL, HttpStatus.CONFLICT, "Skill is used by a sealed workflow"),
                Arguments.of(ErrorCode.SEAL_IMMUTABLE, HttpStatus.FORBIDDEN, "Unsealing is disabled in the startup configuration"),
                Arguments.of(ErrorCode.UNATTENDED_REQUIRES_SEAL, HttpStatus.FORBIDDEN, "Unattended runs require a sealed workflow"),
                Arguments.of(ErrorCode.WEBHOOK_SIGNATURE_INVALID, HttpStatus.UNAUTHORIZED, "Missing or invalid webhook signature"),
                Arguments.of(ErrorCode.NO_TASK_TARGET, HttpStatus.BAD_REQUEST, "No task export target configured"),
                Arguments.of(ErrorCode.PROVIDER_NOT_CONNECTED, HttpStatus.CONFLICT, "Integration provider not connected"),
                Arguments.of(ErrorCode.PROVIDER_NOT_TASK_CAPABLE, HttpStatus.BAD_REQUEST, "Integration provider cannot receive tasks"),
                Arguments.of(ErrorCode.SERVICE_UNAVAILABLE, HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
                Arguments.of(ErrorCode.TOKEN_BUDGET_EXCEEDED, HttpStatus.TOO_MANY_REQUESTS, "Token budget exceeded"),
                Arguments.of(ErrorCode.RATE_LIMITED, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
                Arguments.of(ErrorCode.PAYLOAD_TOO_LARGE, HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
                Arguments.of(ErrorCode.OPERATION_TIMEOUT, HttpStatus.GATEWAY_TIMEOUT, "Operation timed out"),
                Arguments.of(ErrorCode.METHOD_NOT_ALLOWED, HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
                Arguments.of(ErrorCode.RUN_DELETION_DISABLED, HttpStatus.FORBIDDEN, "Deleting runs is disabled"),
                Arguments.of(ErrorCode.INTERNAL_ERROR, HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error")
        );
    }
}
