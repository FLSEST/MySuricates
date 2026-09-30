package com.automation.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

@Getter
public enum ErrorCode {

    RESOURCE_NOT_FOUND(HttpStatus.NOT_FOUND, "Resource not found"),
    MODULE_DISABLED(HttpStatus.NOT_FOUND, "Module disabled"),
    MODULES_NOT_EDITABLE(HttpStatus.FORBIDDEN, "Modules cannot be switched at runtime"),
    VALIDATION_FAILED(HttpStatus.BAD_REQUEST, "Validation failed"),
    INVALID_ARGUMENT(HttpStatus.BAD_REQUEST, "Invalid argument"),
    WORKFLOW_EXECUTION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "Workflow execution failed"),
    CONTAINER_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Container error"),
    STAGING_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Staging error"),
    STATE_CONFLICT(HttpStatus.CONFLICT, "State conflict"),
    WORKFLOW_SEALED(HttpStatus.CONFLICT, "Workflow is sealed"),
    WORKFLOW_TAMPERED(HttpStatus.CONFLICT, "Sealed workflow definition was modified"),
    SKILL_PINNED_BY_SEAL(HttpStatus.CONFLICT, "Skill is used by a sealed workflow"),
    SEAL_IMMUTABLE(HttpStatus.FORBIDDEN, "Unsealing is disabled in the startup configuration"),
    UNATTENDED_REQUIRES_SEAL(HttpStatus.FORBIDDEN, "Unattended runs require a sealed workflow"),
    WEBHOOK_SIGNATURE_INVALID(HttpStatus.UNAUTHORIZED, "Missing or invalid webhook signature"),
    NO_TASK_TARGET(HttpStatus.BAD_REQUEST, "No task export target configured"),
    PROVIDER_NOT_CONNECTED(HttpStatus.CONFLICT, "Integration provider not connected"),
    PROVIDER_NOT_TASK_CAPABLE(HttpStatus.BAD_REQUEST, "Integration provider cannot receive tasks"),
    SERVICE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable"),
    TOKEN_BUDGET_EXCEEDED(HttpStatus.TOO_MANY_REQUESTS, "Token budget exceeded"),
    RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
    PAYLOAD_TOO_LARGE(HttpStatus.PAYLOAD_TOO_LARGE, "Payload too large"),
    OPERATION_TIMEOUT(HttpStatus.GATEWAY_TIMEOUT, "Operation timed out"),
    METHOD_NOT_ALLOWED(HttpStatus.METHOD_NOT_ALLOWED, "Method not allowed"),
    RUN_DELETION_DISABLED(HttpStatus.FORBIDDEN, "Deleting runs is disabled"),
    INTERNAL_ERROR(HttpStatus.INTERNAL_SERVER_ERROR, "Internal server error");

    private final HttpStatus httpStatus;
    private final String defaultMessage;

    ErrorCode(HttpStatus httpStatus, String defaultMessage) {
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }
}
