package com.automation.exception;

import lombok.Getter;

import java.util.Map;

/**
 * An exception that answers with a specific {@link ErrorCode} instead of a generic 4xx/500.
 *
 * <p>With {@code params} set, code and params describe the message completely: the API sends them
 * along and the UI shows its own translation of the code instead of {@code message}, which stays
 * the English text for logs and API clients (TODO 325cc733). Without params the message is free
 * text and the UI shows it as it is.
 */
@Getter
public abstract class CodedException extends RuntimeException {

    private final ErrorCode code;
    private final Map<String, String> params;
    /** Set when the message is one of the {@link ErrorMessage}s rather than the code's own text. */
    private final ErrorMessage errorMessage;

    protected CodedException(ErrorCode code, String message) {
        this(code, message, null);
    }

    protected CodedException(ErrorCode code, String message, Map<String, String> params) {
        super(message);
        this.code = code;
        this.params = params == null ? null : Map.copyOf(params);
        this.errorMessage = null;
    }

    protected CodedException(ErrorMessage errorMessage, Map<String, String> params) {
        super(errorMessage.format(params));
        this.code = errorMessage.getCode();
        this.params = Map.copyOf(params);
        this.errorMessage = errorMessage;
    }
}
