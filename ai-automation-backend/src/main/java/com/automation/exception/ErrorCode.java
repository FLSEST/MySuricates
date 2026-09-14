package com.automation.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    MODULE_DISABLED(HttpStatus.NOT_FOUND, "Module disabled"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Invalid argument"),
    WORKFLOW_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow execution failed"),
    CONTAINER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Container error"),
    STAGING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Staging error"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "State conflict"),
    WORKFLOW_SEALED(HttpStatus.CONFLICT, "Workflow is sealed"),
    WORKFLOW_TAMPERED(HttpStatus.CONFLICT, "Sealed workflow definition was modified"),
    NO_TASK_TARGET(HttpStatus.BAD_REQUEST, "No task export target configured"),
    PROVIDER_NOT_CONNECTED(HttpStatus.CONFLICT, "Integration provider not connected"),
    PROVIDER_NOT_TASK_CAPABLE(HttpStatus.BAD_REQUEST, "Integration provider cannot receive tasks"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    TOKEN_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Token budget exceeded"),
    OPERATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Operation timed out"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
