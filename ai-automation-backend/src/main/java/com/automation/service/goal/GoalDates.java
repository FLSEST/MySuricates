package com.automation.service.goal;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Dates of work packages and milestones (TODO 259f4f66, c60bf914): an ISO timestamp
 * as sent; a plain date ({@code 2026-10-15}) means, for the start, "from the beginning of that day"
 * (00:00:00 UTC), for the end and the due date "until the end of that day" (23:59:59 UTC) –
 * it only becomes overdue the day after. Empty = no date.
 */
final class GoalDates {

    private GoalDates() {
    }

    static Instant start(String value) {
        return parse(value, LocalTime.MIDNIGHT);
    }

    static Instant end(String value) {
        return parse(value, LocalTime.of(23, 59, 59));
    }

    private static Instant parse(String value, LocalTime timeOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String trimmed = value.trim();
        try {
            return trimmed.length() == 10
                    ? LocalDate.parse(trimmed).atTime(timeOfDay).toInstant(ZoneOffset.UTC)
                    : Instant.parse(trimmed);
        } catch (DateTimeParseException e) {
            throw new LocalizedArgumentException(ErrorMessage.INVALID_DATE, Map.of("value", trimmed));
        }
    }

    /** Title of a package or milestone: trimmed, not empty, at most {@code max} characters. */
    static String title(String raw, int max, String what) {
        String title = raw == null ? "" : raw.trim();
        if (title.isEmpty()) {
            throw new IllegalArgumentException(what + " braucht einen Titel");
        }
        if (title.length() > max) {
            throw new IllegalArgumentException("Der Titel ist laenger als " + max + " Zeichen");
        }
        return title;
    }
}
