package com.automation.exception;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import org.springframework.core.MethodParameter;

import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void handleNoResourceFound_returnsNotFoundStatus() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/api/v1/projects/x/container/start");

        ResponseEntity<ErrorResponse> response = handler.handleNoResourceFound(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("No endpoint: POST /api/v1/projects/x/container/start");
    }

    @Test
    void handleEntityNotFound_returnsNotFoundWithMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(
                new jakarta.persistence.EntityNotFoundException("Run not found: run-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("Run not found: run-x");
    }

    /** 2b73ff48: the localized variant (WorkflowMemoryService) carries messageKey + params. */
    @Test
    void handleEntityNotFound_localized_sendsMessageKeyAndParams() {
        ResponseEntity<ErrorResponse> response = handler.handleEntityNotFound(
                new LocalizedEntityNotFoundException("WorkflowMemory", "mem-x"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("WorkflowMemory not found: mem-x");
        assertThat(response.getBody().getMessageKey()).isEqualTo("NAMED_NOT_FOUND");
        assertThat(response.getBody().getParams()).containsEntry("resource", "WorkflowMemory").containsEntry("id", "mem-x");
    }

    @Test
    void handleMethodNotSupported_returnsMethodNotAllowedStatus() {
        var ex = new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/abc-123/execute");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("METHOD_NOT_ALLOWED");
        assertThat(response.getBody().getError()).isEqualTo("Method not allowed: GET /api/v1/workflows/abc-123/execute");
        assertThat(response.getHeaders().getFirst(org.springframework.http.HttpHeaders.ALLOW)).isEqualTo("POST");
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

    // Params tell the UI that code + params describe the message completely, so it may show its own
    // translation (TODO 325cc733). Free-text errors must not carry them, or the UI would hide the text.

    @Test
    void handleNotFound_sendsResourceAndIdAsParams() {
        ResponseEntity<ErrorResponse> response = handler.handleNotFound(new ResourceNotFoundException("Workflow", "abc-123"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("resource", "Workflow", "id", "abc-123"));
    }

    @Test
    void handleHardening_passesTheParamsOfTheException() {
        var ex = new HardeningException(ErrorCode.WORKFLOW_SEALED, "Workflow 'Nightly' is sealed — unseal it before deleting it",
                java.util.Map.of("name", "Nightly"));

        ResponseEntity<ErrorResponse> response = handler.handleHardening(ex);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("WORKFLOW_SEALED");
        assertThat(response.getBody().getError()).isEqualTo("Workflow 'Nightly' is sealed — unseal it before deleting it");
        assertThat(response.getBody().getParams()).containsExactlyEntriesOf(java.util.Map.of("name", "Nightly"));
    }

    @Test
    void handleHardening_sendsNoParamsForFreeText() {
        ResponseEntity<ErrorResponse> response = handler.handleHardening(
                new HardeningException(ErrorCode.WORKFLOW_TAMPERED, "Sealed workflow 'x' was not started: skill changed"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).isNull();
    }

    @Test
    void handleIllegalArgument_sendsNoParams() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(new IllegalArgumentException("Invalid cron expression: x"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).isNull();
    }

    @Test
    void handleGeneral_marksItsFixedTextAsTranslatable() {
        ResponseEntity<ErrorResponse> response = handler.handleGeneral(new RuntimeException("secret detail"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).isEmpty();
    }

    @Test
    void handleMethodNotSupported_sendsMethodAndPathAsParams() {
        var ex = new HttpRequestMethodNotSupportedException("GET", java.util.List.of("POST"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRequestURI()).thenReturn("/api/v1/workflows/abc-123/execute");

        ResponseEntity<ErrorResponse> response = handler.handleMethodNotSupported(ex, request);

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("method", "GET", "path", "/api/v1/workflows/abc-123/execute"));
    }

    @Test
    void handleTokenBudgetExceeded_sendsUsedAndBudgetAsParams() {
        ResponseEntity<ErrorResponse> response = handler.handleTokenBudgetExceeded(new TokenBudgetExceededException(1000, 1500));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getParams()).containsExactlyInAnyOrderEntriesOf(
                java.util.Map.of("used", "1500", "budget", "1000"));
    }

    // ResponseStatusException keeps the status the controller gave; before, the catch-all answered
    // every one of them with 500 INTERNAL_ERROR (TODO 1bad5275).

    @Test
    void handleResponseStatus_keepsNotFound() {
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND, "Report not found"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RESOURCE_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("Report not found");
    }

    @Test
    void handleResponseStatus_keepsBadRequest() {
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid report name"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    void handleResponseStatus_keepsAnUnmappedStatus_andPicksTheCodeByItsClass() {
        ResponseEntity<ErrorResponse> clientError = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.GONE, "gone"));
        ResponseEntity<ErrorResponse> serverError = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Could not read report"));

        assertThat(clientError.getStatusCode()).isEqualTo(HttpStatus.GONE);
        assertThat(clientError.getBody()).isNotNull();
        assertThat(clientError.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(serverError.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(serverError.getBody()).isNotNull();
        assertThat(serverError.getBody().getCode()).isEqualTo("INTERNAL_ERROR");
        assertThat(serverError.getBody().getError()).isEqualTo("Could not read report");
    }

    @Test
    void handleResponseStatus_fallsBackToTheCodesText_withoutReason() {
        ResponseEntity<ErrorResponse> response = handler.handleResponseStatus(
                new org.springframework.web.server.ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getError()).isEqualTo("Resource not found");
    }

    // Messages from ErrorMessage carry their key, so the UI can tell apart the many texts one code stands for.

    @Test
    void handleIllegalArgument_sendsKeyAndParams_forALocalizedMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalArgument(
                new LocalizedArgumentException(ErrorMessage.INVALID_CRON, java.util.Map.of("expression", "x")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("INVALID_ARGUMENT");
        assertThat(response.getBody().getMessageKey()).isEqualTo("INVALID_CRON");
        assertThat(response.getBody().getParams()).containsExactlyEntriesOf(java.util.Map.of("expression", "x"));
        assertThat(response.getBody().getError()).isEqualTo("Invalid cron expression: x");
    }

    @Test
    void handleIllegalState_sendsKey_forALocalizedMessage() {
        ResponseEntity<ErrorResponse> response = handler.handleIllegalState(
                new LocalizedStateException(ErrorMessage.STABLE_INSTANCE_NOT_RUNNING));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey()).isEqualTo("STABLE_INSTANCE_NOT_RUNNING");
    }

    @Test
    void handleNoSuchElement_sendsKey_forANamedResource() {
        ResponseEntity<ErrorResponse> response = handler.handleNoSuchElement(new ElementNotFoundException("Chain run", "r-1"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey()).isEqualTo("NAMED_NOT_FOUND");
        assertThat(response.getBody().getError()).isEqualTo("Chain run not found: r-1");
    }

    @Test
    void handleMissingParameter_sendsKeyAndName() throws Exception {
        ResponseEntity<ErrorResponse> response = handler.handleMissingParameter(
                new org.springframework.web.bind.MissingServletRequestParameterException("projectId", "String"));

        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMessageKey()).isEqualTo("MISSING_PARAMETER");
        assertThat(response.getBody().getParams()).containsEntry("name", "projectId");
    }

    @Test
    void handleHardening_sendsKey_forAMessageOfItsCode() {
        ResponseEntity<ErrorResponse> response = handler.handleHardening(new HardeningException(
                ErrorMessage.RUN_RETENTION_LOCKED, java.util.Map.of("current", "90", "requested", "30")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getCode()).isEqualTo("RUN_DELETION_DISABLED");
        assertThat(response.getBody().getMessageKey()).isEqualTo("RUN_RETENTION_LOCKED");
        assertThat(response.getBody().getError()).endsWith(": 90 -> 30 days");
    }
}
