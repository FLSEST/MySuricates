package com.automation.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.Instant;
import java.util.Map;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ErrorResponse {

    private final String code;
    private final String error;
    private final String timestamp;
    private final Map<String, String> fieldErrors;
    private final String details;
    /** Set when code and params describe the error completely; the UI then translates the code. */
    private final Map<String, String> params;

    public static ErrorResponse of(ErrorCode errorCode, String message) {
        return of(errorCode, message, null);
    }

    public static ErrorResponse of(ErrorCode errorCode, String message, Map<String, String> params) {
        return ErrorResponse.builder()
                .code(errorCode.name())
                .error(message != null ? message : errorCode.getDefaultMessage())
                .timestamp(Instant.now().toString())
                .params(params)
                .build();
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getDefaultMessage());
    }
}
