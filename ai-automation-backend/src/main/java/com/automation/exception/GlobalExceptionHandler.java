package com.automation.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), ex.getParams());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElement(NoSuchElementException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(TokenBudgetExceededException.class)
    public ResponseEntity<ErrorResponse> handleTokenBudgetExceeded(TokenBudgetExceededException ex) {
        log.warn("Token budget exceeded: {}", ex.getMessage());
        return respond(ErrorCode.TOKEN_BUDGET_EXCEEDED, ex.getMessage(), Map.of(
                "used", String.valueOf(ex.getUsedTokens()), "budget", String.valueOf(ex.getBudgetTokens())));
    }

    @ExceptionHandler(WorkflowExecutionException.class)
    public ResponseEntity<ErrorResponse> handleExecutionError(WorkflowExecutionException ex) {
        log.error("Workflow execution error", ex);
        return respond(ErrorCode.WORKFLOW_EXECUTION_FAILED, ex.getMessage());
    }

    @ExceptionHandler(ContainerException.class)
    public ResponseEntity<ErrorResponse> handleContainerError(ContainerException ex) {
        log.error("Container error", ex);
        return respond(ErrorCode.CONTAINER_ERROR, ex.getMessage());
    }

    @ExceptionHandler(StagingException.class)
    public ResponseEntity<ErrorResponse> handleStagingError(StagingException ex) {
        log.error("Staging error", ex);
        return respond(ErrorCode.STAGING_ERROR, ex.getMessage());
    }

    @ExceptionHandler(TailscaleException.class)
    public ResponseEntity<ErrorResponse> handleTailscaleError(TailscaleException ex) {
        log.error("Tailscale error", ex);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(TaskExportException.class)
    public ResponseEntity<ErrorResponse> handleTaskExport(TaskExportException ex) {
        log.warn("Task export error [{}]: {}", ex.getCode(), ex.getMessage());
        return respond(ex.getCode(), ex.getMessage(), ex.getParams());
    }

    @ExceptionHandler(HardeningException.class)
    public ResponseEntity<ErrorResponse> handleHardening(HardeningException ex) {
        log.warn("Hardening rule [{}]: {}", ex.getCode(), ex.getMessage());
        return respond(ex.getCode(), ex.getMessage(), ex.getParams());
    }

    @ExceptionHandler(ModuleChangeException.class)
    public ResponseEntity<ErrorResponse> handleModuleChange(ModuleChangeException ex) {
        log.warn("Module switch refused [{}]: {}", ex.getCode(), ex.getMessage());
        return respond(ex.getCode(), ex.getMessage(), ex.getParams());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn("State conflict: {}", ex.getMessage());
        return respond(ErrorCode.STATE_CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return respond(ErrorCode.INVALID_ARGUMENT, "Request body is missing or malformed");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        Map<String, String> fieldErrors = new HashMap<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            fieldErrors.put(error.getField(), error.getDefaultMessage());
        }

        ErrorResponse response = ErrorResponse.builder()
                .code(ErrorCode.VALIDATION_FAILED.name())
                .error("Validation failed")
                .timestamp(java.time.Instant.now().toString())
                .fieldErrors(fieldErrors)
                .build();
        return ResponseEntity.status(ErrorCode.VALIDATION_FAILED.getHttpStatus()).body(response);
    }

    @ExceptionHandler(java.util.concurrent.TimeoutException.class)
    public ResponseEntity<ErrorResponse> handleTimeout(java.util.concurrent.TimeoutException ex) {
        log.warn("Operation timed out: {}", ex.getMessage());
        return respond(ErrorCode.OPERATION_TIMEOUT, ex.getMessage() != null ? ex.getMessage() : "Operation timed out");
    }

    // Spring MVC binding failures. Without explicit handlers they fall into handleGeneral
    // (the advice wins over DefaultHandlerExceptionResolver) and surface as 500 INTERNAL_ERROR.
    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex) {
        log.warn("Missing request parameter: {}", ex.getParameterName());
        return respond(ErrorCode.INVALID_ARGUMENT, "Missing required parameter: " + ex.getParameterName());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid value for request parameter '{}': {}", ex.getName(), ex.getValue());
        return respond(ErrorCode.INVALID_ARGUMENT, "Invalid value for parameter '" + ex.getName() + "'");
    }

    // Unknown API path: ResourceHttpRequestHandler's "/**" fallback throws this instead of matching
    // any @RequestMapping. Without an explicit handler it falls into handleGeneral and answers 500
    // INTERNAL_ERROR with a logged stack trace, even though it's a routine 404 (client typo, unbuilt
    // feature, stale bookmark) — see TODO c8f122ac.
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResourceFound(HttpServletRequest request) {
        String message = "No endpoint: " + request.getMethod() + " " + request.getRequestURI();
        log.debug(message);
        return respond(ErrorCode.RESOURCE_NOT_FOUND, message);
    }

    // Known path, wrong HTTP verb (e.g. GET on a POST-only endpoint). Also fell into handleGeneral
    // as 500 before this handler existed.
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                                   HttpServletRequest request) {
        String message = "Method not allowed: " + ex.getMethod() + " " + request.getRequestURI();
        log.debug(message);
        // RFC 9110 §15.5.6: a 405 must carry the allowed methods.
        String[] supported = ex.getSupportedMethods();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(ErrorCode.METHOD_NOT_ALLOWED.getHttpStatus());
        if (supported != null && supported.length > 0) {
            builder.header(HttpHeaders.ALLOW, String.join(", ", supported));
        }
        return builder.body(ErrorResponse.of(ErrorCode.METHOD_NOT_ALLOWED, message,
                Map.of("method", String.valueOf(ex.getMethod()), "path", String.valueOf(request.getRequestURI()))));
    }

    // Repositories/services that look up by id with EntityNotFoundException (e.g. an unknown run id
    // passed to the memory extraction) were answered with 500 — it is a client error.
    @ExceptionHandler(EntityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex) {
        log.debug("Entity not found: {}", ex.getMessage());
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage() != null ? ex.getMessage() : "Entity not found");
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Internal server error", Map.of());
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return respond(code, message, null);
    }

    /** @param params non-null when code and params describe the message completely (see {@link CodedException}) */
    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, String> params) {
        return ResponseEntity.status(code.getHttpStatus()).body(ErrorResponse.of(code, message, params));
    }
}
