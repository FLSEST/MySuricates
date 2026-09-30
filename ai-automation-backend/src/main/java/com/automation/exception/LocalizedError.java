package com.automation.exception;

import java.util.Map;

/**
 * An exception whose message is one of the {@link ErrorMessage}s: the API answers with its code,
 * the English text, and {@code messageKey} + {@code params} for the UI's own translation.
 */
public interface LocalizedError {

    ErrorMessage errorMessage();

    Map<String, String> params();
}
