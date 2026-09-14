package com.automation.exception;

import lombok.Getter;

/**
 * A runtime module switch was refused: the instance is not UI-editable, a hardening switch was
 * targeted, or the change would violate a module dependency. Carries the specific
 * {@link ErrorCode} so the API answers with a structured 4xx.
 */
@Getter
public class ModuleChangeException extends RuntimeException {

    private final ErrorCode code;

    public ModuleChangeException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
