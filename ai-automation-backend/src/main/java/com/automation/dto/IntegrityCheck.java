package com.automation.dto;

/**
 * Result of checking the {@code integrity} field of an imported definition (pool file, workflow export
 * file) against its content — TODO b18939a6.
 *
 * @param status   what the check found
 * @param declared the value the file carries ({@code sha256:<hex>}); {@code null} when it has none
 * @param actual   the value computed from the content
 */
public record IntegrityCheck(Status status, String declared, String actual) {

    public enum Status {
        /** The declared checksum matches the content. */
        VERIFIED,
        /** The file carries no checksum — not verifiable (older exports, hand-written files). */
        MISSING,
        /** The declared checksum does not match the content, or names an unsupported algorithm. */
        MISMATCH
    }

    public boolean mismatch() {
        return status == Status.MISMATCH;
    }
}
