package com.automation.exception;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.core.MethodParameter;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;

class GlobalExceptionHandlerTest {

    private GlobalExceptionHandler handler;

    @BeforeEach
    void setUp() {
        handler = new GlobalExceptionHandler();
    }

    @Test
    void handleNotFound_returnsNotFoundStatus() {
        var ex = new ResourceNotFoundException("Workflow", "abc-123");

        ResponseEntity<ErrorResponse> response = handler.handleNotFound(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).contains("Workflow");
        assertThat(response.getBody().getTimestamp()).isNotBlank();
    }

    @Test
    void handleNoSuchElement_returnsNotFoundStatus() {
        var ex = new NoSuchElementException("Element missing");

        ResponseEntity<ErrorResponse> response = handler.handleNoSuchElement(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("Element missing");
    }

    @Test
    void handleExecutionError_returnsInternalServerError() {
        var ex = new WorkflowExecutionException("Step 3 failed");

        ResponseEntity<ErrorResponse> response = handler.handleExecutionError(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("WORKFLOW_EXECUTION_FAILED");
        assertThat(response.getBody().getError()).isEqualTo("Step 3 failed");
    }

    @Test
    void handleContainerError_returnsInternalServerError() {
        var ex = new ContainerException("Docker timeout");

        ResponseEntity<ErrorResponse> response = handler.handleContainerError(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("CONTAINER_ERROR");
        assertThat(response.getBody().getError()).isEqualTo("Docker timeout");
    }

    @Test
    void handleStagingError_returnsInternalServerError() {
        var ex = new StagingException("Snapshot failed");

        ResponseEntity<ErrorResponse> response = handler.handleStagingError(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("STAGING_ERROR");
        assertThat(response.getBody().getError()).isEqualTo("Snapshot failed");
    }

    @Test
    void handleTailscaleError_returnsServiceUnavailable() {
        var ex = new TailscaleException("Not connected");

        ResponseEntity<ErrorResponse> response = handler.handleTailscaleError(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("SERVICE_UNAVAILABLE");
        assertThat(response.getBody().getError()).isEqualTo("Not connected");
    }

    @Test
    void handleIllegalArgument_returnsBadRequest() {
        var ex = new IllegalArgumentException("Invalid name");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getBody().getError()).isEqualTo("Invalid name");
    }

    @Test
    void handleIllegalState_returnsConflict() {
        var ex = new IllegalStateException("Already running");

        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("STATE_CONFLICT");
        assertThat(response.getBody().getError()).isEqualTo("Already running");
    }

    @Test
    void handleValidation_returnsBadRequestWithFieldErrors() throws Exception {
        var bindingResult = new BeanPropertyBindingResult(new Object(), "request");
        bindingResult.addError(new FieldError("request", "name", "must not be blank"));
        bindingResult.addError(new FieldError("request", "email", "must be valid"));

        MethodParameter param = new MethodParameter(
                Object.class.getMethod("toString"), -1);
        var ex = new MethodArgumentNotValidException(param, bindingResult);

        ResponseEntity<ErrorResponse> response = handler.handleValidation(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(response.getBody().getError()).isEqualTo("Validation failed");
        assertThat(response.getBody().getFieldErrors())
                .containsEntry("name", "must not be blank")
                .containsEntry("email", "must be valid");
    }

    @Test
    void handleGeneral_returnsInternalServerError() {
        var ex = new RuntimeException("Something unexpected");

        ResponseEntity<ErrorResponse> response = handler.handleGeneral(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(response.getBody().getError()).isEqualTo("Internal server error");
    }

    @Test
    void handleMissingParameter_returnsBadRequestNamingTheParameter() {
        var ex = new org.springframework.web.bind.MissingServletRequestParameterException("projectId", "String");

        ResponseEntity<ErrorResponse> response = handler.handleMissingParameter(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getBody().getError()).isEqualTo("Missing required parameter: projectId");
    }

    @Test
    void handleTypeMismatch_returnsBadRequestNamingTheParameter() {
        var ex = new org.springframework.web.method.annotation.MethodArgumentTypeMismatchException(
                "BOGUS", com.automation.entity.AuditSeverity.class, "severity", null, null);

        ResponseEntity<ErrorResponse> response = handler.handleTypeMismatch(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getBody().getError()).isEqualTo("Invalid value for parameter 'severity'");
    }
}
