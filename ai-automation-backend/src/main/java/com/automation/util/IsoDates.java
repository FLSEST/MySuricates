package com.automation.util;

import com.automation.exception.ErrorMessage;
import com.automation.exception.LocalizedArgumentException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Map;

/**
 * Date fields of API requests: an ISO timestamp is taken as sent; a plain date ({@code 2026-10-15}) means
 * a fixed time of that day in UTC – the beginning for a start, the last second for an end or a due date,
 * so that it only becomes overdue the day after. Empty means no date; anything else is a localized
 * {@link ErrorMessage#INVALID_DATE} (400). Shared by the goal plan (work packages, milestones, TODO 259f4f66)
 * and the due date of TODOs (TODO 6968dc90), which used to answer 500 for a plain date.
 */
public final class IsoDates {

    private static final LocalTime END_OF_DAY = LocalTime.of(23, 59, 59);

    private IsoDates() {
    }

    /** A plain date becomes 00:00:00 UTC of that day. */
    public static Instant startOfDay(String value) {
        return parse(value, LocalTime.MIDNIGHT);
    }

    /** A plain date becomes 23:59:59 UTC of that day. */
    public static Instant endOfDay(String value) {
        return parse(value, END_OF_DAY);
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
}
