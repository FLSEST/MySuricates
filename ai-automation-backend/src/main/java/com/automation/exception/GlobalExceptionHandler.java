package com.automation.exception;

import jakarta.persistence.EntityNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.validation.FieldError;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    // The three "not found" handlers log method and path (no stack trace): a 404 on a write (e.g. a
    // retry or resume) used to leave no trace at all — WARN, TODO fe06ebf9. GET/HEAD 404s are routine
    // polling of stale or deleted ids (run pages, lists) and stay on DEBUG so they do not flood the log.
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex, HttpServletRequest request) {
        logNotFound(request, ex.getMessage());
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage(), ex.getParams());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElement(NoSuchElementException ex, HttpServletRequest request) {
        logNotFound(request, ex.getMessage());
        if (ex instanceof LocalizedError localized) return respond(localized);
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
        return respond(ex);
    }

    @ExceptionHandler(HardeningException.class)
    public ResponseEntity<ErrorResponse> handleHardening(HardeningException ex) {
        log.warn("Hardening rule [{}]: {}", ex.getCode(), ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(ModuleChangeException.class)
    public ResponseEntity<ErrorResponse> handleModuleChange(ModuleChangeException ex) {
        log.warn("Module switch refused [{}]: {}", ex.getCode(), ex.getMessage());
        return respond(ex);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Invalid argument: {}", ex.getMessage());
        if (ex instanceof LocalizedError localized) return respond(localized);
        return respond(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        log.warn("State conflict: {}", ex.getMessage());
        if (ex instanceof LocalizedError localized) return respond(localized);
        return respond(ErrorCode.STATE_CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadableBody(HttpMessageNotReadableException ex) {
        log.warn("Unreadable request body: {}", ex.getMessage());
        return respond(ErrorMessage.MALFORMED_BODY, Map.of());
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
        return respond(ErrorMessage.MISSING_PARAMETER, Map.of("name", ex.getParameterName()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        log.warn("Invalid value for request parameter '{}': {}", ex.getName(), ex.getValue());
        return respond(ErrorMessage.INVALID_PARAMETER_VALUE, Map.of("name", ex.getName()));
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
    public ResponseEntity<ErrorResponse> handleEntityNotFound(EntityNotFoundException ex, HttpServletRequest request) {
        logNotFound(request, ex.getMessage());
        if (ex instanceof LocalizedError localized) return respond(localized);
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage() != null ? ex.getMessage() : "Entity not found");
    }

    // Controllers that throw ResponseStatusException (DocsController, ReportsController) meant the status
    // they gave — the catch-all below turned every one of them into a 500 (TODO 1bad5275).
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<ErrorResponse> handleResponseStatus(ResponseStatusException ex) {
        HttpStatusCode status = ex.getStatusCode();
        ErrorCode code = codeFor(status);
        String message = ex.getReason() != null ? ex.getReason() : code.getDefaultMessage();
        if (status.is5xxServerError()) {
            log.error("Request failed with {}: {}", status.value(), message, ex);
        } else {
            log.warn("Request refused with {}: {}", status.value(), message);
        }
        return ResponseEntity.status(status).body(ErrorResponse.of(code, message));
    }

    private static ErrorCode codeFor(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ErrorCode.INVALID_ARGUMENT;
            case 404 -> ErrorCode.RESOURCE_NOT_FOUND;
            case 405 -> ErrorCode.METHOD_NOT_ALLOWED;
            case 409 -> ErrorCode.STATE_CONFLICT;
            case 503 -> ErrorCode.SERVICE_UNAVAILABLE;
            case 504 -> ErrorCode.OPERATION_TIMEOUT;
            default -> status.is4xxClientError() ? ErrorCode.INVALID_ARGUMENT : ErrorCode.INTERNAL_ERROR;
        };
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Internal server error", Map.of());
    }

    private static void logNotFound(HttpServletRequest request, String message) {
        String method = request.getMethod();
        if ("GET".equals(method) || "HEAD".equals(method)) {
            log.debug("Not found: {} {} – {}", method, request.getRequestURI(), message);
        } else {
            log.warn("Not found: {} {} – {}", method, request.getRequestURI(), message);
        }
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return respond(code, message, null);
    }

    private ResponseEntity<ErrorResponse> respond(CodedException ex) {
        if (ex.getErrorMessage() != null) return respond(ex.getErrorMessage(), ex.getParams());
        return respond(ex.getCode(), ex.getMessage(), ex.getParams());
    }

    private ResponseEntity<ErrorResponse> respond(LocalizedError error) {
        return respond(error.errorMessage(), error.params());
    }

    private ResponseEntity<ErrorResponse> respond(ErrorMessage message, Map<String, String> params) {
        return ResponseEntity.status(message.getCode().getHttpStatus()).body(ErrorResponse.of(message, params));
    }

    /** @param params non-null when code and params describe the message completely (see {@link CodedException}) */
    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message, Map<String, String> params) {
        return ResponseEntity.status(code.getHttpStatus()).body(ErrorResponse.of(code, message, params));
    }
}
