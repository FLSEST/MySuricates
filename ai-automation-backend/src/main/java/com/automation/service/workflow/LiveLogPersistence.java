package com.automation.service.workflow;

import java.util.Locale;

/**
 * Per-workflow choice whether a run's live log is kept after the run: {@code null} follows the system
 * default, {@code on}/{@code off} override it. An operating setting, not part of the definition.
 */
public final class LiveLogPersistence {

    public static final String ON = "on";
    public static final String OFF = "off";
    /** Request value that clears the override; stored as {@code null}. */
    public static final String DEFAULT = "default";

    private LiveLogPersistence() {}

    /** Request value → stored value; blank or {@code default} clears it, anything unknown is rejected (400). */
    public static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String lower = value.strip().toLowerCase(Locale.ROOT);
        return switch (lower) {
            case DEFAULT -> null;
            case ON, OFF -> lower;
            default -> throw new IllegalArgumentException(
                    "Invalid liveLogPersist: '" + value + "' (valid: default, on, off)");
        };
    }

    /** Whether to keep the log, given the workflow's stored value and the system default. */
    public static boolean resolve(String stored, boolean systemDefault) {
        if (ON.equals(stored)) return true;
        if (OFF.equals(stored)) return false;
        return systemDefault;
    }
}
