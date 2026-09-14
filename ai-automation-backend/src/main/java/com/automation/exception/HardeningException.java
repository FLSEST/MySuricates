package com.automation.exception;

import lombok.Getter;

/**
 * A workflow hardening rule refused an operation (sealed workflow changed, tampered definition,
 * unattended run of an unsealed workflow, invalid webhook signature). Carries the specific
 * {@link ErrorCode} so the API answers with a structured 4xx instead of a 500.
 */
@Getter
public class HardeningException extends RuntimeException {

    private final ErrorCode code;

    public HardeningException(ErrorCode code, String message) {
        super(message);
        this.code = code;
    }
}
