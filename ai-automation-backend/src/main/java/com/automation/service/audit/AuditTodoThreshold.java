package com.automation.service.audit;

import com.automation.entity.AuditSeverity;

import java.util.Locale;
import java.util.Optional;

/**
 * From which severity an open finding gets a managed TODO by itself – {@code metadata.audit.todoMinSeverity}
 * of the audit workflow (TODO d8607f77). The workflow decides, not the code: until then the threshold was
 * fixed at CRITICAL/HIGH. Lighter findings still get a TODO at the push of a button in the Audit Center.
 */
enum AuditTodoThreshold {
    CRITICAL, HIGH, MEDIUM, LOW,
    /** No managed TODO by itself; only at the push of a button. */
    NONE;

    /** Without {@code todoMinSeverity}: the threshold that applied before it could be configured. */
    static final AuditTodoThreshold DEFAULT = HIGH;

    /** Does an open finding of this severity get a managed TODO? */
    boolean admits(AuditSeverity severity) {
        return this != NONE && severity != null && severity.compareTo(AuditSeverity.valueOf(name())) <= 0;
    }

    /**
     * @return the threshold named by {@code value} (case does not matter), {@link #DEFAULT} without a value,
     *         empty for an unknown one
     */
    static Optional<AuditTodoThreshold> parse(Object value) {
        if (value == null || String.valueOf(value).isBlank()) return Optional.of(DEFAULT);
        try {
            return Optional.of(valueOf(String.valueOf(value).trim().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
