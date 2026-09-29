package com.automation.exception;

import java.util.Map;

/**
 * A workflow hardening rule refused an operation (sealed workflow changed, tampered definition,
 * unattended run of an unsealed workflow, invalid webhook signature). Carries the specific
 * {@link ErrorCode} so the API answers with a structured 4xx instead of a 500.
 */
public class HardeningException extends CodedException {

    public HardeningException(ErrorCode code, String message) {
        super(code, message);
    }

    /** @param params describe the message completely — see {@link CodedException} */
    public HardeningException(ErrorCode code, String message, Map<String, String> params) {
        super(code, message, params);
    }
}
