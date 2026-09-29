package com.automation.exception;

import java.util.Map;

/**
 * A runtime module switch was refused: the instance is not UI-editable, a hardening switch was
 * targeted, or the change would violate a module dependency. Carries the specific
 * {@link ErrorCode} so the API answers with a structured 4xx.
 */
public class ModuleChangeException extends CodedException {

    public ModuleChangeException(ErrorCode code, String message) {
        super(code, message);
    }

    /** @param params describe the message completely — see {@link CodedException} */
    public ModuleChangeException(ErrorCode code, String message, Map<String, String> params) {
        super(code, message, params);
    }
}
