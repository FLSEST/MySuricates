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
    /** An {@link ErrorMessage} key when the code alone does not tell which message this is. */
    private final String messageKey;

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

    public static ErrorResponse of(ErrorMessage errorMessage, Map<String, String> params) {
        Map<String, String> values = params == null ? Map.of() : params;
        return ErrorResponse.builder()
                .code(errorMessage.getCode().name())
                .error(errorMessage.format(values))
                .timestamp(Instant.now().toString())
                .params(values)
                .messageKey(errorMessage.name())
                .build();
    }

    /**
     * Body for a caught exception: its {@link ErrorMessage} when it has one, else {@code fallback}
     * with its message as free text. For controllers that answer a caught exception themselves.
     */
    public static ErrorResponse from(Exception exception, ErrorCode fallback) {
        if (exception instanceof LocalizedError localized) {
            return of(localized.errorMessage(), localized.params());
        }
        return of(fallback, exception.getMessage());
    }

    public static ErrorResponse of(ErrorCode errorCode) {
        return of(errorCode, errorCode.getDefaultMessage());
    }
}
