package com.automation.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(CapabilityNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleCapabilityNotFound(CapabilityNotFoundException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ErrorResponse> handleNoSuchElement(NoSuchElementException ex) {
        return respond(ErrorCode.RESOURCE_NOT_FOUND, ex.getMessage());
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

    @ExceptionHandler(TailscaleException.class)
    public ResponseEntity<ErrorResponse> handleTailscaleError(TailscaleException ex) {
        log.error("Tailscale error", ex);
        return respond(ErrorCode.SERVICE_UNAVAILABLE, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ErrorResponse> handleIllegalArgument(IllegalArgumentException ex) {
        return respond(ErrorCode.INVALID_ARGUMENT, ex.getMessage());
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ErrorResponse> handleIllegalState(IllegalStateException ex) {
        return respond(ErrorCode.STATE_CONFLICT, ex.getMessage());
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

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(Exception ex) {
        log.error("Unhandled exception", ex);
        return respond(ErrorCode.INTERNAL_ERROR, "Internal server error");
    }

    private ResponseEntity<ErrorResponse> respond(ErrorCode code, String message) {
        return ResponseEntity.status(code.getHttpStatus()).body(ErrorResponse.of(code, message));
    }
}
